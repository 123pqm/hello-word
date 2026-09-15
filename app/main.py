from typing import Annotated
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import logging
import os

import jwt
from fastapi import Depends, FastAPI, HTTPException, Path, Query, Response,Header,APIRouter, UploadFile, File, HTTPException
from fastapi.security import APIKeyHeader
from pymysql import IntegrityError
from pymysql.connections import Connection
from starlette.concurrency import run_in_threadpool
from pathlib import Path

from app.database import get_database, initialize_database
from app.schemas import WordCreate, WordListResponse, WordResponse,account_resign,resign_response,login_response,account_login,chose_book_response,book_detail,cet_4_response
from pwdlib import PasswordHash
import shutil
import uuid

password_hasher = PasswordHash.recommended()
logger = logging.getLogger(__name__)

JWT_ALGORITHM = "HS256"
TOKEN_EXPIRE_SECONDS = 30 * 60

# Authorization 必须使用安全方案，Swagger 会忽略普通 Header 参数中的此名称。
# 保留现有的原始 token / Bearer token 请求格式；这里只读取头，不验证 JWT。
authorization_header = APIKeyHeader(
    name="Authorization",
    scheme_name="AuthorizationToken",
    description="填写登录返回的 token，或 Bearer <token>，不要加双引号。",
)


def get_jwt_secret_key() -> str:
    """我们自己写的配置函数：密钥由 database.py 已加载的 .env 提供。"""
    secret_key = os.getenv("JWT_SECRET_KEY", "")
    if len(secret_key.encode("utf-8")) < 32:
        raise RuntimeError("请在 .env 中设置足够长的随机 JWT_SECRET_KEY")
    return secret_key


def create_access_token(user_id: int) -> str:
    """我们自己写的函数：调用 PyJWT 签发 30 分钟有效的登录凭证。"""
    now = datetime.now(timezone.utc)
    token_data = {
        "sub": str(user_id),  # 用户 ID 必须转成字符串，不放密码或密码哈希。
        "iat": now,
        "exp": now + timedelta(seconds=TOKEN_EXPIRE_SECONDS),
    }
    return jwt.encode(token_data, get_jwt_secret_key(), algorithm=JWT_ALGORITHM)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 配置错误在启动时报告，不能使用默认或临时随机密钥继续运行。
    get_jwt_secret_key()
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
    authorization: str = Header()
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
    authorization: str = Header()
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
    authorization: str = Header()
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
    status_code=200,
    tags=["登录"],
)
def login(
    payload: account_login,
    response: Response,
    database: Annotated[Connection, Depends(get_database)],
) -> login_response:
    the_account = payload.account.strip()
    # 密码不能 strip()，空格也可能是用户密码的一部分。
    the_password = payload.password

    if not the_account or not the_password:
        raise HTTPException(
            status_code=422,
            detail="账号/密码不可以为空",
        )

    try:
        with database.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, account, password_hash
                FROM users
                WHERE account = %s
                """,
                (the_account,),
            )
            user = cursor.fetchone()

        # 先确认账号存在，再用 pwdlib 验证密码。
        if user is None:
            raise HTTPException(
                status_code=401,
                detail="账号或密码错误",
            )

        if not password_hasher.verify(the_password, user["password_hash"]):
            raise HTTPException(
                status_code=401,
                detail="账号或密码错误",
            )

        # 只有账号、密码都正确时才签发 token，不需要写入 users 表。
        access_token = create_access_token(user["id"])
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        return login_response(
            reply="登录成功",
            account=user["account"],
            access_token=access_token,
            token_type="bearer",
            expires_in=TOKEN_EXPIRE_SECONDS,
        )

    except HTTPException:
        raise
    except Exception as error:
        logger.exception("登录接口发生内部错误")
        raise HTTPException(
            status_code=500,
            detail="登录失败，请稍后重试",
        ) from error

@app.put(
    "/user/chose",
    response_model=chose_book_response,
    status_code=200,
    tags=["选择单词书"]
)

def chose_book(
    payload:book_detail,
    database:Annotated[Connection,Depends(get_database)],
    authorization: str = Header()
)->chose_book_response:
   id=payload.id.strip()
   book_id=playload.book_id.strip()

   if not id or not book_id:
    raise HTTPException(
        status_code=422,
        detail="单词书不存在"
    )

    try:
        with database.cursor() as cursor:
            cursor.execute(
                """
                UPDATE users
                SET book_id = %s
                WHERE id = %s;
                """,
                (book_id,id)
            )
            database.commit()
    except IntegrityError as error:
        database.rollback()
        raise HTTPException(
            status_code=409,
            detail="网络请求失败"
        ) from error
    return chose_book_response(
        reply="成功更改单词书"
    )

@app.get(
    "/user/chose/cet4",
    response_model=cet_4_response,
    status_code=200,
    tags=["单词详情"]
)
def cet4_detail(
    start_index: int,
    end_index: int,
    book_id: int,
    database: Annotated[Connection, Depends(get_database)],
    authorization: str = Depends(authorization_header)
) -> cet_4_response:

    if start_index <= 0:
        raise HTTPException(
            status_code=400,
            detail="start_index 必须大于 0"
        )

    if end_index < start_index:
        raise HTTPException(
            status_code=400,
            detail="end_index 要大于等于 start_index"
        )

    try:
        with database.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, word, meaning, pos
                FROM cet4
                WHERE id BETWEEN %s AND %s
                ORDER BY id;
                """,
                (start_index, end_index)
            )

            rows = cursor.fetchall()

        data = [
            WordResponse(
                id=row["id"],
                word=row["word"],
                meaning=row["meaning"],
                pos=row["pos"]
            )
            for row in rows
        ]

        return cet_4_response(data=data)

    except Exception as error:
        raise HTTPException(
            status_code=500,
            detail=f"数据库查询失败: {str(error)}"
        )


router = APIRouter(prefix="/video", tags=["视频"])

# 视频保存目录
UPLOAD_DIR = Path("uploads/videos")
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


@router.post("/upload")
async def upload_video(
    file: UploadFile = File(...)
):
    # 1. 判断是不是视频
    if not file.content_type or not file.content_type.startswith("video/"):
        raise HTTPException(
            status_code=400,
            detail="只能上传视频文件"
        )

    # 2. 获取后缀，比如 .mp4
    suffix = Path(file.filename or "").suffix.lower()

    if suffix not in [".mp4", ".mov", ".mkv", ".avi"]:
        raise HTTPException(
            status_code=400,
            detail="不支持的视频格式"
        )

    # 3. 生成唯一文件名
    new_filename = f"{uuid.uuid4()}{suffix}"

    file_path = UPLOAD_DIR / new_filename

    # 4. 保存文件
    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"视频保存失败: {str(e)}"
        )

    finally:
        await file.close()

    # 5. 返回结果
    return {
        "code": 200,
        "message": "上传成功",
        "data": {
            "originalFilename": file.filename,
            "filename": new_filename,
            "path": str(file_path)
        }
    }
app.include_router(router)