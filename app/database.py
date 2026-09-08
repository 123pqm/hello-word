"""MySQL 连接、建表和 FastAPI 数据库依赖。密码从本机 .env 读取。"""

from collections.abc import Generator
import os
from pathlib import Path
import re

from dotenv import load_dotenv
import pymysql
from pymysql.connections import Connection
from pymysql.cursors import DictCursor


BACKEND_DIR = Path(__file__).resolve().parent.parent
# 环境变量优先于 .env；无论在哪个目录启动，都读取 backend/.env。
load_dotenv(BACKEND_DIR / ".env", override=False, interpolate=False)


def get_database_name(database_name: str | None = None) -> str:
    """数据库名不能用 SQL 值占位符，所以先严格校验再放进 SQL。"""
    name = (
        database_name
        if database_name is not None
        else os.getenv("MYSQL_DATABASE", "movie_vocab")
    )
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", name):
        raise ValueError("MYSQL_DATABASE 只能包含 1～64 个英文字母、数字或下划线。")
    return name


def connect_database(
    *, database_name: str | None = None, select_database: bool = True
) -> Connection:
    """创建连接。DictCursor 使查询结果是可按列名读取的 Python 字典。"""
    password = os.getenv("MYSQL_PASSWORD")
    if not password or password == "CHANGE_ME":
        raise RuntimeError("请先在 backend/.env 填好 MYSQL_PASSWORD，然后重启程序。")

    return pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=password,
        database=get_database_name(database_name) if select_database else None,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=False,
        connect_timeout=5,
        read_timeout=30,
        write_timeout=30,
        sql_mode="STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION",
    )


def create_words_table(connection: Connection) -> None:
    """创建表；已有表及其中的数据会保留。建表是 DDL，不属于数据插入事务。"""
    with connection.cursor() as cursor:
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS words (
                id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                word VARCHAR(50) NOT NULL UNIQUE,
                meaning VARCHAR(200) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """
        )


def initialize_database() -> None:
    """首次运行时建库、建表。原 SQLite 数据由 migrate_sqlite.py 单独迁移。"""
    name = get_database_name()
    connection = connect_database(select_database=False)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                f"CREATE DATABASE IF NOT EXISTS `{name}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
            )
        connection.select_db(name)
        create_words_table(connection)
        create_users_table(connection)
        connection.commit()
    finally:
        connection.close()


def get_database() -> Generator[Connection, None, None]:
    """为每次请求提供独立连接，请求结束后自动关闭。"""
    connection = connect_database()

    try:
        yield connection
    finally:
        connection.close()

def create_users_table(connection:Connection)->None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS users(
               id INt NOT NULL AUTO_INCREMENT PRIMARY KEY,
               account VARCHAR(15) NOT NULL UNIQUE,
               password_hash VARCHAR(255) NOT NULL
            )
            ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """
        )
