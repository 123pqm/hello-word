"""按登录用户保存单词收藏；内容取自其电影，客户端不能伪造用户或释义。"""
import logging
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pymysql.connections import Connection

from app.auth import get_current_user_id
from app.database import get_database

router = APIRouter(prefix="/favorites/words", tags=["收藏单词"])
logger = logging.getLogger(__name__)
Database = Annotated[Connection, Depends(get_database)]
CurrentUser = Annotated[int, Depends(get_current_user_id)]


def word_key(word: str) -> str:
    key = word.strip().lower()
    if not key or len(key) > 200:
        raise HTTPException(422, "单词不能为空或过长")
    return key


class FavoriteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    movie_id: int = Field(gt=0)
    word: str = Field(min_length=1, max_length=100)

    @field_validator("word")
    @classmethod
    def strip_word(cls, value):
        if not value.strip():
            raise ValueError("单词不能为空")
        return value.strip()


class FavoriteWord(BaseModel):
    id: int
    word: str
    meaning: str | None
    sentence_text: str | None
    source_movie_id: int | None
    source_movie_name: str | None
    start_time: float | None
    end_time: float | None
    created_at: datetime


class FavoriteResponse(BaseModel):
    code: int = 200
    data: FavoriteWord


class FavoritePage(BaseModel):
    items: list[FavoriteWord]
    next_before_id: int | None = None


class FavoritesResponse(BaseModel):
    code: int = 200
    data: FavoritePage


class FavoriteStatus(BaseModel):
    word: str
    is_favorite: bool


class FavoriteStatusResponse(BaseModel):
    code: int = 200
    data: FavoriteStatus


@router.put("", response_model=FavoriteResponse)
def save_favorite(body: FavoriteRequest, database: Database, user_id: CurrentUser, response: Response):
    response.headers["Cache-Control"] = "no-store"
    key = word_key(body.word)
    try:
        with database.cursor() as cursor:
            # 与删除电影共用行锁，快照写入完成后才允许删除来源记录。
            cursor.execute("SELECT file_name, status FROM movies WHERE id = %s AND user_id = %s FOR UPDATE",
                           (body.movie_id, user_id))
            movie = cursor.fetchone()
            if movie is None:
                raise HTTPException(404, "电影不存在或无权访问")
            if movie["status"] != "completed":
                raise HTTPException(409, "电影尚未分析完成")
            cursor.execute("""SELECT word, meaning, sentence_text, start_time, end_time FROM movie_words
                              WHERE movie_id = %s AND LOWER(TRIM(word)) = %s
                              ORDER BY start_time, id LIMIT 1 FOR UPDATE""", (body.movie_id, key))
            source = cursor.fetchone()
            if source is None:
                raise HTTPException(404, "该电影中没有这个单词")
            cursor.execute("""INSERT INTO favorite_words
                (user_id, word_key, word, meaning, sentence_text, source_movie_id, source_movie_name, start_time, end_time)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE word=VALUES(word), meaning=VALUES(meaning),
                    sentence_text=VALUES(sentence_text), source_movie_id=VALUES(source_movie_id),
                    source_movie_name=VALUES(source_movie_name), start_time=VALUES(start_time), end_time=VALUES(end_time)
                """, (user_id, key, source["word"], source["meaning"], source["sentence_text"],
                       body.movie_id, movie["file_name"], source["start_time"], source["end_time"]))
            # 读取当前已锁定的版本；幂等 upsert 没改值时也不能读到鉴权时建立的旧快照。
            cursor.execute("SELECT * FROM favorite_words WHERE user_id = %s AND word_key = %s FOR UPDATE", (user_id, key))
            result = FavoriteResponse(data=FavoriteWord(**cursor.fetchone()))
        database.commit()
        return result
    except HTTPException:
        database.rollback()
        raise
    except Exception:
        database.rollback()
        logger.exception("保存用户 %s 收藏失败", user_id)
        raise HTTPException(503, "收藏失败，请稍后重试") from None


@router.get("/status", response_model=FavoriteStatusResponse)
def favorite_status(database: Database, user_id: CurrentUser, response: Response,
                    word: Annotated[str, Query(min_length=1, max_length=100)]):
    response.headers["Cache-Control"] = "no-store"
    key = word_key(word)
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT id FROM favorite_words WHERE user_id = %s AND word_key = %s", (user_id, key))
            return FavoriteStatusResponse(data=FavoriteStatus(word=key, is_favorite=cursor.fetchone() is not None))
    except Exception:
        logger.exception("读取收藏状态失败")
        raise HTTPException(503, "暂时无法读取收藏状态") from None


@router.get("", response_model=FavoritesResponse)
def list_favorites(database: Database, user_id: CurrentUser, response: Response,
                   limit: Annotated[int, Query(ge=1, le=100)] = 30,
                   before_id: Annotated[int | None, Query(gt=0)] = None):
    response.headers["Cache-Control"] = "no-store"
    sql = "SELECT * FROM favorite_words WHERE user_id = %s"
    params = [user_id]
    if before_id is not None:
        sql += " AND id < %s"
        params.append(before_id)
    sql += " ORDER BY id DESC LIMIT %s"
    params.append(limit + 1)
    try:
        with database.cursor() as cursor:
            cursor.execute(sql, tuple(params))
            rows = cursor.fetchall()
        items = rows[:limit]
        return FavoritesResponse(data=FavoritePage(items=items,
            next_before_id=items[-1]["id"] if len(rows) > limit else None))
    except Exception:
        logger.exception("读取收藏列表失败")
        raise HTTPException(503, "暂时无法读取收藏列表") from None


@router.delete("", status_code=204)
def delete_favorite(database: Database, user_id: CurrentUser,
                    word: Annotated[str, Query(min_length=1, max_length=100)]):
    key = word_key(word)
    try:
        with database.cursor() as cursor:
            cursor.execute("DELETE FROM favorite_words WHERE user_id = %s AND word_key = %s", (user_id, key))
        database.commit()
    except Exception:
        database.rollback()
        logger.exception("取消收藏失败")
        raise HTTPException(503, "取消收藏失败，请稍后重试") from None
    return Response(status_code=204, headers={"Cache-Control": "no-store"})
