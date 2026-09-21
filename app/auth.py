"""从经过验证的 Bearer token 取得用户，不能信任客户端提交的 user_id。"""
import os
from typing import Annotated

import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pymysql.connections import Connection

from app.database import get_database

bearer = HTTPBearer(auto_error=False)


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
        cursor.execute("SELECT id FROM users WHERE id = %s", (user_id,))
        if cursor.fetchone() is None:
            raise unauthorized
    return user_id
