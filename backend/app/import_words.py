"""读取四级词库，合并同词释义后导入 MySQL；支持重复执行。"""

import argparse
from pathlib import Path
import sys

# 兼容 IDE 的“运行当前文件”和 python app/import_words.py。
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.database import BACKEND_DIR, connect_database, initialize_database


DEFAULT_SOURCE = BACKEND_DIR / "data" / "cet4.txt"


def read_cet4(source: Path) -> tuple[list[tuple[str, str, str]], int]:
    """先校验整个文件；重复单词的不同释义保留在同一条记录中。"""
    entries: dict[str, list[tuple[str, str]]] = {}
    source_count = 0
    with source.open("r", encoding="utf-8-sig") as file:
        for line_number, line in enumerate(file, 1):
            if not line.strip():
                continue
            parts = line.strip().split("\t", 1)
            detail = parts[1].strip().split(maxsplit=1) if len(parts) == 2 else []
            if len(detail) != 2 or not parts[0].strip():
                raise ValueError(f"第 {line_number} 行格式错误，需要：单词<Tab>词性 释义")
            word = parts[0].strip()
            pos, meaning = detail
            if len(word) > 50 or len(pos) > 32 or len(meaning) > 200:
                raise ValueError(f"第 {line_number} 行 {word!r} 的字段超长（上限：50 / 32 / 200）")
            senses = entries.setdefault(word, [])
            if (pos, meaning) not in senses:
                senses.append((pos, meaning))
            source_count += 1

    if not entries:
        raise ValueError("词库文件没有可导入的词汇")

    rows = []
    for word, senses in entries.items():
        pos = " / ".join(dict.fromkeys(pos for pos, _ in senses))
        meaning = (
            senses[0][1]
            if len(senses) == 1
            else "；".join(f"{pos} {meaning}" for pos, meaning in senses)
        )
        if len(pos) > 32 or len(meaning) > 200:
            raise ValueError(f"{word!r} 合并后的词性或释义超长（上限：32 / 200）")
        rows.append((word, pos, meaning))
    return rows, source_count


def import_words(source: str | Path = DEFAULT_SOURCE) -> int:
    source = Path(source).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"四级词汇文件不存在：{source}")
    # 在连接数据库前完成解析，文件错误不会造成部分导入。
    rows, source_count = read_cet4(source)
    print(f"读取 {source}：{source_count} 行，合并后 {len(rows)} 个单词")

    initialize_database()
    database = connect_database()
    try:
        with database.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO cet4 (word, pos, meaning)
                VALUES (%s, %s, %s) AS incoming
                ON DUPLICATE KEY UPDATE pos = incoming.pos, meaning = incoming.meaning
                """,
                rows,
            )
        database.commit()
        print(f"四级词汇导入完成：已写入或更新 {len(rows)} 个单词")
        return len(rows)
    except Exception:
        database.rollback()
        raise
    finally:
        database.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=DEFAULT_SOURCE, help="词库文件路径")
    args = parser.parse_args()
    try:
        import_words(args.source)
    except Exception as error:
        print(f"导入失败：{error}", file=sys.stderr)
        sys.exit(1)
