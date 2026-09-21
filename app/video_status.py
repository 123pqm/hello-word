"""Read committed background-job state; querying never restarts a movie job."""
import logging
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response
from pydantic import BaseModel
from pymysql.connections import Connection
from fastapi.responses import FileResponse
from app.services.movie_cover import get_cover

from app.database import get_database
from app.auth import get_current_user_id

router = APIRouter(prefix="/video", tags=["视频"])
logger = logging.getLogger(__name__)


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
