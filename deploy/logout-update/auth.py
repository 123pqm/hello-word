"""从经过验证的 Bearer token 取得用户，不能信任客户端提交的 user_id。"""
import os
import hashlib
from typing import Annotated

import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pymysql.connections import Connection

from app.database import get_database

bearer = HTTPBearer(auto_error=False)


def token_fingerprint(token: str) -> str:
    # 对签名覆盖的内容取摘要，避免签名 base64 的等价编码绕过撤销。
    return hashlib.sha256(token.rsplit('.', 1)[0].encode('ascii')).hexdigest()


def get_current_user_id(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    database: Annotated[Connection, Depends(get_database)],
) -> int:
    unauthorized = HTTPException(
        status_code=401, detail="登录已失效，请重新登录",
        headers={"WWW-Authenticate": "Bearer"},
    )
    if credentials is None:
        raise unauthorized
    key = os.getenv("JWT_SECRET_KEY", "")
    if len(key.encode("utf-8")) < 32:
        raise HTTPException(status_code=503, detail="登录配置暂不可用")
    try:
        claims = jwt.decode(credentials.credentials, key, algorithms=["HS256"],
                            options={"require": ["sub", "iat", "exp"]})
        subject = claims["sub"]
        if not isinstance(subject, str) or not subject.isascii() or not subject.isdigit():
            raise ValueError("invalid subject")
        user_id = int(subject)
        if not 0 < user_id <= 2147483647:
            raise ValueError("invalid user id")
    except (jwt.InvalidTokenError, ValueError, TypeError):
        raise unauthorized from None
    with database.cursor() as cursor:
        cursor.execute("""SELECT id FROM users WHERE id = %s AND NOT EXISTS
            (SELECT 1 FROM revoked_access_tokens WHERE token_hash = %s)""",
            (user_id, token_fingerprint(credentials.credentials)))
        if cursor.fetchone() is None:
            raise unauthorized
    return user_id
