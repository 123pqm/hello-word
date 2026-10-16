"""Read committed background-job state; querying never restarts a movie job."""
import logging
import os
import time
import jwt
from fastapi.security import HTTPAuthorizationCredentials
from app.auth import bearer, token_fingerprint
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response
from pydantic import BaseModel
from pymysql.connections import Connection
from fastapi.responses import FileResponse
from app.services.movie_cover import get_cover
from app.services import movie_cover

from app.database import get_database
from app.auth import get_current_user_id

router = APIRouter(prefix="/video", tags=["视频"])
logger = logging.getLogger(__name__)


@router.delete("/{movie_id}", status_code=204)
def delete_movie(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)],
):
    """在同一事务内删除自己的电影和关联闪卡，与后台写入共用电影行锁。"""
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT id FROM movies WHERE id = %s AND user_id = %s FOR UPDATE", (movie_id, user_id))
            if cursor.fetchone() is None:
                raise HTTPException(404, "电影不存在或已删除")
            cursor.execute("DELETE FROM movie_words WHERE movie_id = %s", (movie_id,))
            cursor.execute("DELETE FROM movies WHERE id = %s AND user_id = %s", (movie_id, user_id))
        database.commit()
    except HTTPException:
        database.rollback()
        raise
    except Exception:
        database.rollback()
        logger.exception("删除电影 %s 失败", movie_id)
        raise HTTPException(503, "删除失败，请稍后重试") from None
    return Response(status_code=204, headers={"Cache-Control": "no-store"})


@router.get("/{movie_id}/file", response_class=FileResponse)
def get_movie_file(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)],
):
    """本机文件关联失效时播放已上传的副本，支持播放器的 Range 跳转。"""
    with database.cursor() as cursor:
        cursor.execute("SELECT file_path FROM movies WHERE id = %s AND user_id = %s", (movie_id, user_id))
        movie = cursor.fetchone()
    if movie is None or not movie["file_path"]:
        raise HTTPException(404, "视频不存在")
    # 只允许 uploads/videos 内的视频，兼容旧数据库里的绝对路径。
    root = movie_cover.VIDEO_DIR.resolve()
    filename = movie["file_path"].replace("\\", "/").rsplit("/", 1)[-1]
    video = (root / filename).resolve()
    if video.parent != root or not video.is_file() or video.suffix.lower() not in {".mp4", ".mov", ".mkv", ".avi"}:
        raise HTTPException(404, "服务器视频文件已不存在，请重新选择手机原视频")
    return FileResponse(video, headers={"Cache-Control": "private, no-store"})


@router.get("/{movie_id}/cover", response_class=FileResponse)
def get_movie_cover(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)],
):
    with database.cursor() as cursor:
        cursor.execute("SELECT file_path FROM movies WHERE id = %s AND user_id = %s", (movie_id, user_id))
        movie = cursor.fetchone()
    if movie is None or not movie["file_path"]:
        raise HTTPException(status_code=404, detail="视频不存在")
    try:
        cover = get_cover(movie["file_path"])
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="视频或封面工具不可用") from None
    except Exception:
        logger.exception("生成电影 %s 封面失败", movie_id)
        raise HTTPException(status_code=503, detail="暂时无法生成封面") from None
    return FileResponse(cover, media_type="image/jpeg", headers={"Cache-Control": "private, no-store"})


class MovieHistoryItem(BaseModel):
    id: int
    file_name: str | None = None
    status: str | None = None
    created_at: datetime | None = None


class MovieHistoryPage(BaseModel):
    items: list[MovieHistoryItem]
    next_before_id: int | None = None


class MovieHistoryResponse(BaseModel):
    code: int = 200
    data: MovieHistoryPage


@router.get("/history", response_model=MovieHistoryResponse)
def get_movie_history(
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)],
    response: Response,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
    before_id: Annotated[int | None, Query(gt=0)] = None,
) -> MovieHistoryResponse:
    """只返回当前用户的电影，按上传编号倒序；游标翻页避免新增上传造成重复。"""
    response.headers["Cache-Control"] = "no-store"
    sql = "SELECT id, file_name, status, created_at FROM movies WHERE user_id = %s"
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
        return MovieHistoryResponse(data=MovieHistoryPage(
            items=items,
            next_before_id=items[-1]["id"] if len(rows) > limit else None,
        ))
    except Exception:
        logger.exception("读取用户电影历史失败")
        raise HTTPException(status_code=503, detail="暂时无法读取电影历史，请稍后重试") from None


class MovieStatus(BaseModel):
    movie_id: int
    status: Literal["pending", "processing", "completed", "failed"]
    error_message: str | None = None
    matched_word_count: int = 0


class MovieStatusResponse(BaseModel):
    code: int = 200
    message: str = "查询成功"
    data: MovieStatus


class MatchedMovieWord(BaseModel):
    word: str
    meaning: str
    # 新处理的视频为所在句子的起止秒数；历史记录仍保留原词时间。
    start_time: float
    end_time: float
    sentence_text: str | None = None


class MovieWords(BaseModel):
    movie_id: int
    words: list[MatchedMovieWord]


class MovieWordsResponse(BaseModel):
    code: int = 200
    message: str = "查询成功"
    data: MovieWords


@router.get("/{movie_id}/words", response_model=MovieWordsResponse)
def get_movie_words(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    response: Response,
    user_id: Annotated[int, Depends(get_current_user_id)],
) -> MovieWordsResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT id, status FROM movies WHERE id = %s AND user_id = %s", (movie_id, user_id))
            movie = cursor.fetchone()
            if movie is None:
                raise HTTPException(status_code=404, detail="视频任务不存在")
            if movie["status"] != "completed":
                raise HTTPException(status_code=409, detail="视频尚未处理成功，暂时没有可用的闪卡结果")
            cursor.execute(
                """SELECT word, meaning, start_time, end_time, sentence_text
                   FROM movie_words WHERE movie_id = %s
                   ORDER BY start_time, end_time, word""",
                (movie_id,),
            )
            return MovieWordsResponse(data=MovieWords(movie_id=movie_id, words=cursor.fetchall()))
    except HTTPException:
        raise
    except Exception:
        logger.exception("读取视频 %s 匹配单词失败", movie_id)
        raise HTTPException(status_code=503, detail="暂时无法读取匹配单词，请稍后重试") from None


@router.get("/{movie_id}/status", response_model=MovieStatusResponse)
def get_movie_status(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    response: Response,
    user_id: Annotated[int, Depends(get_current_user_id)],
) -> MovieStatusResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        with database.cursor() as cursor:
            cursor.execute(
                """SELECT id, status FROM movies WHERE id = %s AND user_id = %s""", (movie_id, user_id)
            )
            row = cursor.fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="视频任务不存在")
            count = 0
            if row["status"] == "completed":
                cursor.execute(
                    "SELECT COUNT(*) AS total FROM movie_words WHERE movie_id = %s",
                    (movie_id,),
                )
                count = cursor.fetchone()["total"]
            return MovieStatusResponse(data=MovieStatus(
                movie_id=row["id"], status=row["status"], matched_word_count=count,
                # Detailed worker errors remain in server logs / database.
                error_message="视频处理失败，请重试或检查后端日志" if row["status"] == "failed" else None,
            ))
    except HTTPException:
        raise
    except Exception:
        logger.exception("查询视频 %s 状态失败", movie_id)
        raise HTTPException(status_code=503, detail="暂时无法查询视频状态，请稍后重试") from None


# A movie-scoped capability; never put the account's Bearer token in a URL.
@router.post("/{movie_id}/playback")
def create_playback(
    movie_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
    user_id: Annotated[int, Depends(get_current_user_id)],
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
    response: Response,
):
    get_movie_file(movie_id, database, user_id)  # Check ownership and file availability.
    key = os.environ["JWT_SECRET_KEY"]
    claims = jwt.decode(credentials.credentials, key, algorithms=["HS256"])
    expires = min(int(claims["exp"]), int(time.time()) + 1800)
    ticket = jwt.encode({"sub": str(user_id), "movie_id": movie_id,
                         "aud": "movie-playback", "exp": expires,
                         "session": token_fingerprint(credentials.credentials)}, key, algorithm="HS256")
    response.headers["Cache-Control"] = "no-store"
    return {"data": {"ticket": ticket, "expires_at": expires}}


@router.get("/{movie_id}/stream", response_class=FileResponse)
def stream_movie(
    movie_id: Annotated[int, Path(gt=0)],
    ticket: Annotated[str, Query(max_length=2048)],
    database: Annotated[Connection, Depends(get_database)],
):
    key = os.getenv("JWT_SECRET_KEY", "")
    if len(key.encode()) < 32:
        raise HTTPException(503, "登录配置暂不可用")
    try:
        claims = jwt.decode(ticket, key, algorithms=["HS256"], audience="movie-playback",
                            options={"require": ["sub", "exp", "aud", "movie_id", "session"]})
        user_id = int(claims["sub"])
        if claims["movie_id"] != movie_id:
            raise ValueError("wrong movie")
    except (jwt.InvalidTokenError, ValueError, TypeError):
        raise HTTPException(401, "播放链接已失效，请重新登录或重新读取视频") from None
    with database.cursor() as cursor:
        cursor.execute("""SELECT id FROM users WHERE id = %s AND NOT EXISTS
            (SELECT 1 FROM revoked_access_tokens WHERE token_hash = %s)""",
            (user_id, claims["session"]))
        if cursor.fetchone() is None:
            raise HTTPException(401, "播放链接已失效")
    result = get_movie_file(movie_id, database, user_id)
    result.headers["Referrer-Policy"] = "no-referrer"
    return result
