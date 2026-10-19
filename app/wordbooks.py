"""词书目录和按位置分页的词条列表。"""
from typing import Annotated
import logging
import re

from fastapi import APIRouter, Depends, HTTPException, Query
from pymysql.connections import Connection

from app.auth import get_current_user_id
from app.database import get_database
from app.schemas import WordbookWordListResponse
from app.services.wordbook_service import BOOKS, validate_book
from app.services.spelling_service import suggest_words


router = APIRouter(prefix="/wordbooks", tags=["词书"], dependencies=[Depends(get_current_user_id)])


@router.get("/{book_id}/suggestions")
def spelling_suggestions(book_id: int, database: Annotated[Connection, Depends(get_database)],
                         q: Annotated[str, Query(min_length=1, max_length=40)]):
    try:
        validate_book(book_id)
    except ValueError as error:
        raise HTTPException(404, str(error)) from error
    query = q.strip().lower()
    if not re.fullmatch(r"[a-z][a-z'-]{1,39}", query):
        return {"data": [], "book_id": book_id}
    with database.cursor() as cursor:
        if book_id == 1:
            cursor.execute("SELECT id, word, meaning, pos FROM cet4")
        else:
            cursor.execute("SELECT id, word, meaning, pos FROM wordbook_words WHERE book_id=%s", (book_id,))
        vocabulary = cursor.fetchall()
    try:
        return {"data": suggest_words(query, vocabulary), "book_id": book_id}
    except Exception as error:
        logging.getLogger(__name__).exception("拼写候选生成失败")
        raise HTTPException(503, "猜你想搜暂时不可用，请稍后重试") from error


@router.get("")
def list_wordbooks(database: Annotated[Connection, Depends(get_database)]):
    with database.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS total FROM cet4")
        counts = {1: cursor.fetchone()["total"]}
        cursor.execute("SELECT book_id, COUNT(*) AS total FROM wordbook_words GROUP BY book_id")
        counts.update({row["book_id"]: row["total"] for row in cursor.fetchall()})
    return {"data": [book | {"total": counts.get(book_id, 0)} for book_id, book in BOOKS.items()]}


@router.get("/{book_id}/words", response_model=WordbookWordListResponse)
def book_words(book_id: int, database: Annotated[Connection, Depends(get_database)],
               start_index: Annotated[int, Query(ge=1)] = 1,
               end_index: Annotated[int, Query(ge=1)] = 20):
    try:
        validate_book(book_id)
    except ValueError as error:
        raise HTTPException(404, str(error)) from error
    limit = end_index - start_index + 1
    if not 1 <= limit <= 100:
        raise HTTPException(422, "每次请请求 1～100 个单词")
    with database.cursor() as cursor:
        if book_id == 1:
            cursor.execute("SELECT COUNT(*) AS total FROM cet4")
            total = cursor.fetchone()["total"]
            cursor.execute("SELECT id, word, meaning, pos FROM cet4 ORDER BY id LIMIT %s OFFSET %s",
                           (limit, start_index - 1))
        else:
            cursor.execute("SELECT COUNT(*) AS total FROM wordbook_words WHERE book_id = %s", (book_id,))
            total = cursor.fetchone()["total"]
            cursor.execute("""SELECT id, word, meaning, pos, frequency FROM wordbook_words
                              WHERE book_id = %s ORDER BY frequency DESC, id ASC LIMIT %s OFFSET %s""",
                           (book_id, limit, start_index - 1))
        rows = cursor.fetchall()
    if total == 0:
        raise HTTPException(503, "词书尚未导入")
    return {"data": rows, "total": total, "book": BOOKS[book_id]}


@router.get("/{book_id}/search")
def search_words(
    book_id: int,
    database: Annotated[Connection, Depends(get_database)],
    q: Annotated[str, Query(min_length=1, max_length=80)],
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
    offset: Annotated[int, Query(ge=0, le=100000)] = 0,
):
    """词书内忽略大小写的包含搜索；精确、前缀、包含依次排序，按需分页。

    输入停止 350ms 后由客户端请求；下一页沿用相同 book_id/q/limit。
    不计算全量总数，多取一条判断 has_more。空白关键词不查询数据库。
    """
    try:
        validate_book(book_id)
    except ValueError as error:
        raise HTTPException(404, str(error)) from error
    query = q.strip().lower()
    if not query:
        raise HTTPException(422, "请输入搜索单词")
    # Treat SQL LIKE wildcard characters as literal input, not wildcard syntax.
    escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    table = "cet4" if book_id == 1 else "wordbook_words"
    scope = "" if book_id == 1 else "book_id = %s AND "
    params = [] if book_id == 1 else [book_id]
    params.extend([f"%{escaped}%", query, escaped + "%", limit + 1, offset])
    with database.cursor() as cursor:
        cursor.execute(f"""SELECT id, word, meaning, pos FROM {table}
            WHERE {scope}LOWER(word) LIKE %s ESCAPE '!'
            ORDER BY CASE WHEN LOWER(word) = %s THEN 0
                WHEN LOWER(word) LIKE %s ESCAPE '!' THEN 1 ELSE 2 END,
                LOWER(word), id
            LIMIT %s OFFSET %s""", tuple(params))
        rows = cursor.fetchall()
    has_more = len(rows) > limit
    return {"data": rows[:limit], "book_id": book_id, "query": query,
            "limit": limit, "has_more": has_more,
            "next_offset": offset + limit if has_more else None}
