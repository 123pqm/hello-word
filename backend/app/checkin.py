"""北京时间登录打卡：每周七格覆盖保存，连续天数单独累计。"""
from datetime import date, datetime, time, timedelta, timezone
import json
import logging
import math
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel
from pymysql.connections import Connection

from app.auth import get_current_user_id
from app.database import get_database

BEIJING = timezone(timedelta(hours=8))
logger = logging.getLogger(__name__)
router = APIRouter(prefix="/user", tags=["学习打卡"])


def beijing_now() -> datetime:
    return datetime.now(BEIJING)


class CheckinDay(BaseModel):
    date: date
    weekday: int  # 周一=1，周日=7；数组下标仍为0..6。
    checked: bool
    is_today: bool


class CheckinData(BaseModel):
    user_id: int
    today: date
    days: list[CheckinDay]
    week_count: int
    streak_days: int
    next_checkin_after_seconds: int


class CheckinResponse(BaseModel):
    code: int = 200
    data: CheckinData


def parse_dates(value) -> list:
    dates = json.loads(value) if isinstance(value, (str, bytes)) else value
    return list(dates) if isinstance(dates, list) and len(dates) == 7 else [None] * 7


def build_status(user_id: int, dates: list, last_date: date | None, streak: int, now: datetime) -> CheckinData:
    today = now.date()
    monday = today - timedelta(days=today.weekday())
    days = []
    for index in range(7):
        day = monday + timedelta(days=index)
        days.append(CheckinDay(date=day, weekday=index + 1,
                               checked=dates[index] == day.isoformat() and day <= today,
                               is_today=day == today))
    ongoing = last_date in (today, today - timedelta(days=1))
    midnight = datetime.combine(today + timedelta(days=1), time.min, tzinfo=BEIJING)
    return CheckinData(user_id=user_id, today=today, days=days,
                       week_count=sum(day.checked for day in days),
                       streak_days=max(0, streak) if ongoing else 0,
                       next_checkin_after_seconds=max(1, math.ceil((midnight - now).total_seconds())))


def checkin_status(database: Connection, user_id: int, *, mark: bool) -> CheckinData:
    try:
        with database.cursor() as cursor:
            cursor.execute(
                "SELECT login_dates, last_login_date, login_streak FROM users WHERE id = %s"
                + (" FOR UPDATE" if mark else ""), (user_id,),
            )
            row = cursor.fetchone()
            if row is None:
                raise HTTPException(status_code=401, detail="用户不存在，请重新登录")
            # 在拿到行锁后取日期，避免跨午夜等待锁的请求写回前一天。
            now = beijing_now()
            today = now.date()
            dates = parse_dates(row.get("login_dates"))
            last_date = row.get("last_login_date")
            streak = row.get("login_streak") or 0
            if mark and (last_date is None or last_date < today):
                streak = streak + 1 if last_date == today - timedelta(days=1) else 1
                dates[today.weekday()] = today.isoformat()
                last_date = today
                cursor.execute(
                    "UPDATE users SET login_dates = %s, last_login_date = %s, login_streak = %s WHERE id = %s",
                    (json.dumps(dates), last_date, streak, user_id),
                )
            result = build_status(user_id, dates, last_date, streak, now)
        if mark:
            database.commit()  # 同一天并发登录先串行读写，后来的请求不会重复累计。
        return result
    except Exception:
        if mark:
            database.rollback()
        raise


@router.post("/checkin", response_model=CheckinResponse)
def mark_checkin(
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)], response: Response,
) -> CheckinResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        return CheckinResponse(data=checkin_status(database, user_id, mark=True))
    except HTTPException:
        raise
    except Exception:
        logger.exception("登录打卡失败")
        raise HTTPException(status_code=503, detail="打卡暂时失败，请稍后重试") from None


@router.get("/checkin", response_model=CheckinResponse)
def read_checkin(
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)], response: Response,
) -> CheckinResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        return CheckinResponse(data=checkin_status(database, user_id, mark=False))
    except HTTPException:
        raise
    except Exception:
        logger.exception("读取打卡失败")
        raise HTTPException(status_code=503, detail="暂时无法读取打卡") from None
