"""短期、单个视频专用的状态凭证，不能用来登录或读取单词/文件。"""
from datetime import datetime, timedelta, timezone
import os
from typing import Annotated

import jwt
from fastapi import APIRouter, Depends, Header, HTTPException, Path, Response
from pymysql.connections import Connection

from app.database import get_database
from app.video_status import MovieStatusResponse, get_movie_status

router = APIRouter(prefix="/video", tags=["视频"])
AUDIENCE = "video-progress"


def create_tracking_token(user_id: int, movie_id: int) -> str:
    now = datetime.now(timezone.utc)
    return jwt.encode({"sub": str(user_id), "movie_id": movie_id, "aud": AUDIENCE,
                       "iat": now, "exp": now + timedelta(hours=24)},
                      os.environ["JWT_SECRET_KEY"], algorithm="HS256")


@router.get("/{movie_id}/progress", response_model=MovieStatusResponse)
def track_progress(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    response: Response,
    tracking_token: Annotated[str | None, Header(alias="X-Video-Tracking")] = None,
):
    try:
        claims = jwt.decode(tracking_token or "", os.environ["JWT_SECRET_KEY"], algorithms=["HS256"],
                            audience=AUDIENCE, options={"require": ["sub", "movie_id", "aud", "iat", "exp"]})
        user_id = int(claims["sub"])
        if user_id <= 0 or type(claims["movie_id"]) is not int or claims["movie_id"] != movie_id:
            raise ValueError("wrong task")
    except (jwt.InvalidTokenError, ValueError, TypeError, KeyError):
        raise HTTPException(401, "视频提醒凭证已失效，请重新登录查看处理结果") from None
    return get_movie_status(movie_id, database, response, user_id)
