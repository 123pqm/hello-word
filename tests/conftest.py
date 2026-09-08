"""MySQL 集成测试只操作本次新建的随机测试库，不清空用户的 movie_vocab。"""

from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.database import connect_database, create_words_table, get_database
from app.main import app


SEED_WORDS = [
    (1, "experience", "经验；经历"),
    (2, "movie", "电影"),
    (3, "vocabulary", "词汇"),
    (4, "transparent", "透明的"),
    (5, "environment", "环境"),
]


def pytest_addoption(parser):
    parser.addoption("--mysql", action="store_true", help="运行真实 MySQL 集成测试")


def pytest_collection_modifyitems(config, items):
    if not config.getoption("--mysql"):
        for item in items:
            if "mysql" in item.keywords:
                item.add_marker(pytest.mark.skip(reason="使用 .\\test.ps1 -MySQL 运行数据库集成测试"))


@pytest.fixture(scope="session")
def mysql_test_database():
    name = "movie_vocab_test_" + uuid4().hex
    connection = connect_database(select_database=False)
    created = False
    try:
        with connection.cursor() as cursor:
            # 不用 IF NOT EXISTS：只有本次确实新建的库才允许在结束后删除。
            cursor.execute(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
        created = True
        connection.select_db(name)
        create_words_table(connection)
        yield name
    finally:
        if created:
            connection.select_db("information_schema")
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE `{name}`")
        connection.close()


@pytest.fixture
def mysql_database(mysql_test_database):
    connection = connect_database(database_name=mysql_test_database)
    try:
        with connection.cursor() as cursor:
            # connection 只连接上面的随机测试库。
            cursor.execute("DELETE FROM words")
            cursor.executemany(
                "INSERT INTO words (id, word, meaning) VALUES (%s, %s, %s)", SEED_WORDS
            )
        connection.commit()
        yield connection
    finally:
        connection.rollback()
        connection.close()


@pytest.fixture
def client(mysql_test_database, mysql_database):
    def test_connection():
        connection = connect_database(database_name=mysql_test_database)
        try:
            yield connection
        finally:
            connection.close()

    app.dependency_overrides[get_database] = test_connection
    # 不进入 TestClient 的 lifespan，避免应用初始化连接实际业务库。
    test_client = TestClient(app)
    try:
        yield test_client
    finally:
        test_client.close()
        app.dependency_overrides.pop(get_database, None)
