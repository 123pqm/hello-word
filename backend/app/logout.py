"""撤销当前登录凭证；数据库持久化，多进程及服务重启后仍有效。"""
import logging
import os
from typing import Annotated

import jwt
from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.security import HTTPAuthorizationCredentials
from pymysql import MySQLError
from pymysql.connections import Connection
from app.database import connect_database, get_database
from app.auth import bearer, get_current_user_id

router = APIRouter(prefix="/user", tags=["用户"])
logger = logging.getLogger(__name__)


def create_revocations_table(database):
    with database.cursor() as cursor:
        cursor.execute("""CREATE TABLE IF NOT EXISTS revoked_access_tokens (
            token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
            expires_at BIGINT NOT NULL,
            INDEX idx_revoked_expiry (expires_at)
        ) ENGINE=InnoDB""")


def initialize_logout_storage():
    database = connect_database()
    try:
        create_revocations_table(database)
        database.commit()
    finally:
        database.close()


@router.post('/logout', status_code=204, summary='退出当前登录')
def logout(
    user_id: Annotated[int, Depends(get_current_user_id)],
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
    database: Annotated[Connection, Depends(get_database)],
):
    # 依赖已完成签名、时效、用户及撤销状态验证；这里仅提取已验证的过期时间。
    from app.auth import token_fingerprint
    try:
        claims = jwt.decode(credentials.credentials, os.environ['JWT_SECRET_KEY'], algorithms=['HS256'],
                            options={'verify_exp': False})
        with database.cursor() as cursor:
            cursor.execute('DELETE FROM revoked_access_tokens WHERE expires_at <= UNIX_TIMESTAMP()')
            cursor.execute('INSERT IGNORE INTO revoked_access_tokens (token_hash, expires_at) VALUES (%s, %s)',
                           (token_fingerprint(credentials.credentials), int(claims['exp'])))
        database.commit()
    except MySQLError:
        database.rollback()
        logger.exception('退出登录失败')
        raise HTTPException(503, '退出登录失败，请稍后重试') from None
    return Response(status_code=204, headers={'Cache-Control': 'no-store'})
