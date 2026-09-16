"""Read committed background-job state; querying never restarts a movie job."""
import logging
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Path, Response
from pydantic import BaseModel
from pymysql.connections import Connection

from app.database import get_database

router = APIRouter(prefix="/video", tags=["视频"])
logger = logging.getLogger(__name__)


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
    start_time: float
    end_time: float


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
) -> MovieWordsResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT id, status FROM movies WHERE id = %s", (movie_id,))
            movie = cursor.fetchone()
            if movie is None:
                raise HTTPException(status_code=404, detail="视频任务不存在")
            if movie["status"] != "completed":
                raise HTTPException(status_code=409, detail="视频尚未处理成功，暂时没有可用的闪卡结果")
            cursor.execute(
                """SELECT word, meaning, start_time, end_time
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
) -> MovieStatusResponse:
    response.headers["Cache-Control"] = "no-store"
    try:
        with database.cursor() as cursor:
            cursor.execute(
                """SELECT id, status FROM movies WHERE id = %s""", (movie_id,)
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
