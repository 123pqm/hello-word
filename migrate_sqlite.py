"""把 SQLite words 表复制到 MySQL。保留原文件和 ID，遇到冲突整批回滚。"""

import argparse
from pathlib import Path
import sqlite3
import sys

import pymysql

from app.database import BACKEND_DIR, connect_database, get_database_name, initialize_database


WordRow = tuple[int, str, str]


def read_sqlite_words(source: Path) -> list[WordRow]:
    """mode=ro 表示只读，路径写错也不会意外创建空 SQLite 文件。"""
    source = source.resolve()
    if not source.is_file():
        raise ValueError(f"找不到原数据库：{source}")
    connection = sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)
    try:
        rows = connection.execute("SELECT id, word, meaning FROM words ORDER BY id").fetchall()
    finally:
        connection.close()

    for word_id, word, meaning in rows:
        if not isinstance(word_id, int) or not 1 <= word_id <= 2147483647:
            raise ValueError(f"原数据 ID={word_id!r} 超出当前 MySQL 表的正整数范围。")
        if not isinstance(word, str) or not 1 <= len(word) <= 50:
            raise ValueError(f"ID={word_id} 的单词必须是 1～50 字符，迁移未开始。")
        if not isinstance(meaning, str) or not 1 <= len(meaning) <= 200:
            raise ValueError(f"ID={word_id} 的释义必须是 1～200 字符，迁移未开始。")
    return rows


def plan_migration(source: list[WordRow], existing: list[WordRow]) -> list[WordRow]:
    """完全相同的记录跳过；同 ID 或同单词但内容不同则报错，不覆盖。"""
    by_id = {row[0]: row for row in existing}
    by_word = {row[1]: row for row in existing}
    pending = []
    for row in source:
        word_id, word, _ = row
        if word_id in by_id:
            if by_id[word_id] == row:
                continue
            raise ValueError(f"MySQL 已有 ID={word_id}，内容不同。请选一个空的目标库。")
        if word in by_word:
            raise ValueError(f"原数据 ID={word_id} 的单词已存在于 MySQL，但 ID 不同。")
        pending.append(row)
    return pending


def read_mysql_words(connection, name: str) -> list[WordRow]:
    """目标库或表尚未创建时返回空列表；不会在预览时建库。"""
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT 1 FROM information_schema.tables "
            "WHERE table_schema = %s AND table_name = 'words'",
            (name,),
        )
        if cursor.fetchone() is None:
            return []
        # name 已经经过 get_database_name 的严格白名单校验。
        cursor.execute(f"SELECT id, word, meaning FROM `{name}`.words ORDER BY id")
        return [(row["id"], row["word"], row["meaning"]) for row in cursor.fetchall()]


def migrate(source: Path, *, dry_run: bool = False) -> tuple[int, int]:
    rows = read_sqlite_words(source)
    name = get_database_name()
    if not dry_run:
        initialize_database()

    connection = connect_database(select_database=False)
    try:
        existing = read_mysql_words(connection, name)
        pending = plan_migration(rows, existing)
        if dry_run:
            return len(pending), len(rows) - len(pending)

        connection.select_db(name)
        with connection.cursor() as cursor:
            if pending:
                cursor.executemany(
                    "INSERT INTO words (id, word, meaning) VALUES (%s, %s, %s)", pending
                )
        # 在提交之前逐条校验；不能把截断、丢字或 ID 变化当作迁移成功。
        actual = {row[0]: row for row in read_mysql_words(connection, name)}
        if any(actual.get(row[0]) != row for row in rows):
            raise RuntimeError("迁移后的数据与 SQLite 不一致，新增记录已回滚。")
        connection.commit()
        return len(pending), len(rows) - len(pending)
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=BACKEND_DIR / "data" / "words.db")
    parser.add_argument("--dry-run", action="store_true", help="只检查连接和冲突，不写入 MySQL")
    args = parser.parse_args()
    try:
        inserted, skipped = migrate(args.source, dry_run=args.dry_run)
    except (ValueError, RuntimeError, sqlite3.Error, pymysql.MySQLError) as error:
        print(f"迁移未完成：{error}", file=sys.stderr)
        print("原 SQLite 文件未修改。若建库已完成，空库/空表会保留。", file=sys.stderr)
        return 1
    action = "预览通过，将新增" if args.dry_run else "迁移完成，新增"
    print(f"{action} {inserted} 条，跳过完全相同记录 {skipped} 条。")
    print(f"目标库：{get_database_name()}；原 SQLite 文件保留。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
