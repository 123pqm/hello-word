"""导入仓库内固定版本的考研词表；可重复执行，不连接第三方 SQL 服务。"""
import hashlib
import json
from pathlib import Path

from app.database import BACKEND_DIR, connect_database, create_wordbook_tables


SOURCE_DIR = BACKEND_DIR / "resources" / "vocabulary" / "netem"
HIGH_FREQUENCY_MIN = 40


def read_netem(source: Path = SOURCE_DIR / "netem_full_list.json") -> list[dict]:
    document = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or len(document) != 1:
        raise ValueError("考研词库顶层格式错误")
    records = next(iter(document.values()))
    if not isinstance(records, list) or not records:
        raise ValueError("考研词库为空")
    ids = set()
    result = []
    for item in records:
        word_id, frequency = item.get("序号"), item.get("词频")
        word, meaning = item.get("单词"), item.get("释义")
        if (type(word_id) is not int or word_id <= 0 or word_id in ids or
                type(frequency) is not int or frequency < 0 or
                not isinstance(word, str) or not 1 <= len(word.strip()) <= 100 or
                not isinstance(meaning, str) or not 1 <= len(meaning.strip()) <= 2000):
            raise ValueError("考研词库存在无效或重复编号的词条")
        for name, limit in (("其他拼写", 2000), ("分类", 100), ("子分类", 100)):
            value = item.get(name)
            if value is not None and (not isinstance(value, str) or len(value) > limit):
                raise ValueError(f"考研词条的{name}格式错误")
        ids.add(word_id)
        result.append({"id": word_id, "word": word.strip(), "meaning": meaning.strip(),
                       "frequency": frequency, "variants": item.get("其他拼写"),
                       "category": item.get("分类"), "subcategory": item.get("子分类")})
    return result


def import_netem() -> dict[int, int]:
    source = SOURCE_DIR / "netem_full_list.json"
    metadata = json.loads((SOURCE_DIR / "SOURCE.json").read_text(encoding="utf-8"))
    if hashlib.sha256(source.read_bytes()).hexdigest() != metadata["sha256"]:
        raise ValueError("考研词库校验和不匹配，导入已取消")
    records = read_netem(source)
    counts = {3: len(records), 4: sum(row["frequency"] >= HIGH_FREQUENCY_MIN for row in records)}
    database = connect_database()
    try:
        create_wordbook_tables(database)
        with database.cursor() as cursor:
            rows = [
                (book_id, row["id"], row["word"], row["meaning"], row["frequency"],
                 row["variants"], row["category"], row["subcategory"])
                for book_id in (3, 4) for row in records
                if book_id == 3 or row["frequency"] >= HIGH_FREQUENCY_MIN
            ]
            cursor.executemany("""
                INSERT INTO wordbook_words (book_id, id, word, meaning, frequency, variants, category, subcategory)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s) AS incoming
                ON DUPLICATE KEY UPDATE word=incoming.word, meaning=incoming.meaning,
                    frequency=incoming.frequency, variants=incoming.variants,
                    category=incoming.category, subcategory=incoming.subcategory
            """, rows)
        database.commit()
    except Exception:
        database.rollback()
        raise
    finally:
        database.close()
    return counts


if __name__ == "__main__":
    print(json.dumps(import_netem()))
