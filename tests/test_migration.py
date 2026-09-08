import hashlib
import sqlite3
from uuid import uuid4

import pymysql
import pytest

from app.database import connect_database, get_database_name
from migrate_sqlite import migrate, plan_migration, read_mysql_words, read_sqlite_words


def make_source(tmp_path, rows):
    source = tmp_path / "中文单词.db"
    with sqlite3.connect(source) as connection:
        connection.execute(
            "CREATE TABLE words (id INTEGER PRIMARY KEY, word TEXT UNIQUE NOT NULL, meaning TEXT NOT NULL)"
        )
        connection.executemany("INSERT INTO words VALUES (?, ?, ?)", rows)
    return source


def test_missing_source_is_not_created(tmp_path):
    source = tmp_path / "missing.db"
    with pytest.raises(ValueError, match="找不到"):
        read_sqlite_words(source)
    assert not source.exists()


def test_sqlite_source_is_read_only_and_preserves_unicode(tmp_path):
    rows = [(7, "it's", "中文 🎬")]
    source = make_source(tmp_path, rows)
    before = hashlib.sha256(source.read_bytes()).digest()
    assert read_sqlite_words(source) == rows
    assert hashlib.sha256(source.read_bytes()).digest() == before


def test_migration_skips_only_identical_rows():
    existing = [(1, "movie", "电影")]
    assert plan_migration(existing + [(9, "hello", "你好")], existing) == [(9, "hello", "你好")]


@pytest.mark.parametrize("existing", [
    [(1, "movie", "不同的释义")],
    [(8, "movie", "电影")],
])
def test_conflicts_are_reported(existing):
    with pytest.raises(ValueError):
        plan_migration([(1, "movie", "电影")], existing)


def test_oversized_source_value_is_rejected(tmp_path):
    source = make_source(tmp_path, [(1, "x" * 51, "释义")])
    with pytest.raises(ValueError, match="1～50"):
        read_sqlite_words(source)


@pytest.mark.parametrize("name", ["", "x` ; DROP DATABASE mysql; --", "a-b", "x" * 65])
def test_database_identifier_is_validated(name):
    with pytest.raises(ValueError):
        get_database_name(name)


def test_missing_password_is_explained(monkeypatch):
    monkeypatch.setenv("MYSQL_PASSWORD", "CHANGE_ME")
    with pytest.raises(RuntimeError, match="MYSQL_PASSWORD"):
        connect_database()


@pytest.mark.mysql
def test_real_migration_retains_ids_and_is_repeatable(tmp_path, monkeypatch, mysql_test_database, mysql_database):
    monkeypatch.setenv("MYSQL_DATABASE", mysql_test_database)
    rows = [(2, "movie", "电影"), (20, "it's", "中文 🎬")]
    source = make_source(tmp_path, rows)
    before = hashlib.sha256(source.read_bytes()).digest()
    assert migrate(source, dry_run=True) == (1, 1)
    assert migrate(source) == (1, 1)
    assert migrate(source) == (0, 2)
    actual = read_mysql_words(mysql_database, mysql_test_database)
    assert all(row in actual for row in rows)
    with mysql_database.cursor() as cursor:
        cursor.execute("INSERT INTO words (word, meaning) VALUES (%s, %s)", ("nextword", "下一个"))
        assert cursor.lastrowid > 20
    mysql_database.rollback()
    assert hashlib.sha256(source.read_bytes()).digest() == before


@pytest.mark.mysql
def test_preview_does_not_create_database(tmp_path, monkeypatch, mysql_database):
    name = "movie_vocab_preview_" + uuid4().hex
    monkeypatch.setenv("MYSQL_DATABASE", name)
    source = make_source(tmp_path, [(1, "movie", "电影")])
    assert migrate(source, dry_run=True) == (1, 0)
    with mysql_database.cursor() as cursor:
        cursor.execute("SELECT 1 FROM information_schema.schemata WHERE schema_name = %s", (name,))
        assert cursor.fetchone() is None


@pytest.mark.mysql
def test_sql_error_rolls_back_entire_import(tmp_path, monkeypatch, mysql_test_database, mysql_database):
    monkeypatch.setenv("MYSQL_DATABASE", mysql_test_database)
    # utf8mb4_bin 唯一索引忽略尾随空格：第二条会在 MySQL 层触发重复键错误。
    source = make_source(tmp_path, [(20, "fresh", "新词"), (21, "movie ", "尾随空格")])
    with pytest.raises(pymysql.IntegrityError):
        migrate(source)
    actual = read_mysql_words(mysql_database, mysql_test_database)
    assert len(actual) == 5
    assert all(row[0] not in (20, 21) for row in actual)
