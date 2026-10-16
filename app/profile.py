"""鸿蒙与其他客户端共用的个人资料、词书选择接口。"""
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field
from pymysql.connections import Connection

from app.auth import get_current_user_id
from app.database import get_database
from app.services.wordbook_service import BOOKS, validate_book

router = APIRouter(prefix="/user", tags=["用户资料"])
Database = Annotated[Connection, Depends(get_database)]
CurrentUser = Annotated[int, Depends(get_current_user_id)]


class SelectWordbookRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    book_id: int = Field(gt=0, strict=True)


class UserProfile(BaseModel):
    user_id: int
    account: str
    book_id: int
    book: dict | None


class UserProfileResponse(BaseModel):
    code: int = 200
    data: UserProfile


def read_profile(database: Connection, user_id: int) -> UserProfileResponse:
    with database.cursor() as cursor:
        cursor.execute("SELECT id, account, book_id FROM users WHERE id = %s", (user_id,))
        row = cursor.fetchone()
    if row is None:
        raise HTTPException(401, "登录已失效，请重新登录")
    return UserProfileResponse(data=UserProfile(
        user_id=row["id"], account=row["account"], book_id=row["book_id"],
        book=BOOKS.get(row["book_id"]),
    ))


def save_wordbook(database: Connection, user_id: int, book_id: int) -> None:
    try:
        validate_book(book_id)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    try:
        with database.cursor() as cursor:
            cursor.execute("UPDATE users SET book_id = %s WHERE id = %s", (book_id, user_id))
        database.commit()
    except Exception:
        database.rollback()
        raise HTTPException(503, "保存词书失败，请稍后重试") from None


@router.get("/me", response_model=UserProfileResponse)
def get_profile(database: Database, user_id: CurrentUser, response: Response):
    response.headers["Cache-Control"] = "no-store"
    return read_profile(database, user_id)


@router.put("/me/wordbook", response_model=UserProfileResponse)
def select_wordbook(payload: SelectWordbookRequest, database: Database,
                    user_id: CurrentUser, response: Response):
    response.headers["Cache-Control"] = "no-store"
    save_wordbook(database, user_id, payload.book_id)
    return read_profile(database, user_id)
