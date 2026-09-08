from typing import Annotated
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Path, Query
from pymysql import IntegrityError
from pymysql.connections import Connection
from starlette.concurrency import run_in_threadpool

from app.database import get_database, initialize_database
from app.schemas import WordCreate, WordListResponse, WordResponse,account_resign,resign_response,login_response,account_login
from pwdlib import PasswordHash

password_hasher = PasswordHash.recommended()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 真正启动服务时才连接 MySQL，导入模块、查看代码不触发建库。
    # PyMySQL 是同步驱动，所以把初始化放在线程池中运行。
    await run_in_threadpool(initialize_database)
    yield


app = FastAPI(
    title="AI 影视词汇学习 API",
    version="0.1.0",
    description="AI 影视词汇学习 HarmonyOS App 的后端服务。",
    lifespan=lifespan,
)


@app.get("/health", tags=["系统"])
async def health() -> dict[str, str]:
    """返回服务健康状态。"""
    return {
        "status": "ok",
        "service": "movie-vocab-api",
        "version": "0.1.0",
        "isok" : "no"
    }


@app.get("/words/{word_id}", response_model=WordResponse, tags=["单词"])
def get_word_by_id(
    word_id: Annotated[int, Path(gt=0)],
    database: Annotated[Connection, Depends(get_database)],
) -> WordResponse:
    """使用 Path 参数，根据主键查询一个单词。"""
    with database.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, word, meaning
            FROM words
            WHERE id = %s
            """,
            (word_id,),
        )
        row = cursor.fetchone()

    if row is None:
        raise HTTPException(status_code=404, detail="没有找到这个单词")

    return WordResponse(**row)


@app.get("/words", response_model=WordListResponse, tags=["单词"])
def search_words(
    database: Annotated[Connection, Depends(get_database)],
    keyword: Annotated[str, Query(min_length=1, max_length=50)],
    limit: Annotated[int, Query(ge=1, le=50)] = 10,
) -> WordListResponse:
    """使用 Query 参数，按英文单词进行模糊搜索。"""
    search_pattern = f"%{keyword.strip().lower()}%"

    with database.cursor() as cursor:
        cursor.execute(
            """
            SELECT COUNT(*) AS total
            FROM words
            WHERE LOWER(word) LIKE %s
            """,
            (search_pattern,),
        )
        total = cursor.fetchone()["total"]
        cursor.execute(
            """
            SELECT id, word, meaning
            FROM words
            WHERE LOWER(word) LIKE %s
            ORDER BY id
            LIMIT %s
            """,
            (search_pattern, limit),
        )
        rows = cursor.fetchall()

    items = [WordResponse(**row) for row in rows]
    return WordListResponse(items=items, total=total)


@app.post(
    "/words",
    response_model=WordResponse,
    status_code=201,
    tags=["单词"],
)
def add_word(
    payload: WordCreate,
    database: Annotated[Connection, Depends(get_database)],
) -> WordResponse:
    normalized_word = payload.word.strip().lower()
    normalized_meaning = payload.meaning.strip()

    if not normalized_word or not normalized_meaning:
        raise HTTPException(
            status_code=422,
            detail="单词和释义不能为空",
        )

    try:
        with database.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO words (word, meaning)
                VALUES (%s, %s)
                """,
                (normalized_word, normalized_meaning),
            )
            new_id = cursor.lastrowid
        database.commit()
    except IntegrityError as error:
        database.rollback()
        if error.args[0] != 1062:
            raise
        raise HTTPException(
            status_code=409,
            detail="这个单词已经存在",
        ) from error

    return WordResponse(
        id=new_id,
        word=normalized_word,
        meaning=normalized_meaning,
    )

@app.post(
    "/user/sign",
    response_model=resign_response,
    status_code=201,
    tags=["注册"]
)
def resign(
    payload:account_resign,
    database:Annotated[Connection,Depends(get_database)],
)->resign_response:
   the_account=payload.account.strip()
   the_password=payload.password

   if not the_account or not the_password:
    raise HTTPException(
        status_code=422,
        detail="账号/密码不可以为空"
    )

   hashed_password = password_hasher.hash(the_password)
    
   try:
        with database.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO users (account,password_hash)
                VALUES(%s,%s)
                """,
                (the_account,hashed_password),
            )
            new_id = cursor.lastrowid
        database.commit()
   except IntegrityError as error:
        database.rollback()
        if error.args[0]!=1062:
         raise
        raise HTTPException(
            status_code=409,
            detail="账户已存在"
        )from error
   return resign_response(
        id=new_id,
        reply="注册成功",
    )

@app.post(
    "/user/login",
    response_model=login_response,
    status_code=201,
    tags=["登录"]
)
def login(
    playload:account_login,
    database:Annotated[Connection,Depends(get_database)],
)->login_response:
  the_account=playload.account.strip()
  the_password=playload.password

  if not the_account or not the_password:
    raise HTTPException(
        status_code=422,
        detail="账号/密码不可以为空"
    )
  

  try:
    with database.cursor() as cursor:
        cursor.execute(
            """
            SELECT account,password_hash
            FROM users
            WHERE account=%s
            """,
            (the_account,)
        )
        user=cursor.fetchone()

        if user is None:
            raise HTTPException(
                status_code=401,
                detail="账号或密码错误"
            )
        
        stored_password_hash=user["password_hash"]

        if not password_hasher.verify(the_password,stored_password_hash):
            raise HTTPException(
                status_code=401,
                detail="账号或者密码错误"
            )

        return login_response(
            reply="登录成功",
            account=user["account"],
        )   
  except HTTPException:
    raise
  except Exception as error:
    raise HTTPException(
        status_code=500,
        detail=f"登录失败：{str(error)}"
    ) 





        
        