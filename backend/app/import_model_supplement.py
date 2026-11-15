"""仅追加模型词库中尚未收录的词条；保存导入清单，不覆盖已有记录。"""
import argparse
import json
from datetime import datetime
from pathlib import Path

from app.database import BACKEND_DIR, connect_database


def import_supplement(source: Path):
    entries = json.loads(source.read_text(encoding="utf-8"))
    connection = connect_database()
    try:
        with connection.cursor() as cursor:
            snapshots = {}
            known = set()
            for table in ("words", "cet4", "wordbook_words"):
                cursor.execute(f"SELECT * FROM {table}")
                snapshots[table] = cursor.fetchall()
                known.update(row["word"].strip().lower() for row in snapshots[table])
            next_id = max((row["id"] for row in snapshots["wordbook_words"]
                           if row["book_id"] == 5), default=0)
            added, skipped = [], []
            for entry in entries:
                word = entry["word"].strip().lower()
                if word in known:
                    continue
                translations = entry.get("translations") or []
                meaning = "；".join(dict.fromkeys(t["translation"].strip()
                                               for t in translations if t.get("translation", "").strip()))
                pos = " / ".join(dict.fromkeys(t["type"].strip()
                                             for t in translations if t.get("type", "").strip()))
                if (not word or not meaning or len(word) > 100 or len(meaning) > 2000
                        or len(pos) > 32 or "\ufffd" in json.dumps(entry, ensure_ascii=False)):
                    skipped.append({"entry": entry, "reason": "乱码、空字段或字段超长"})
                    continue
                next_id += 1
                added.append({"id": next_id, "word": word, "meaning": meaning,
                              "pos": pos, "source_entry": entry})
                known.add(word)
            report = {"source": str(source.resolve()), "book_id": 5,
                      "added": added, "skipped": skipped}
            folder = BACKEND_DIR / "work" / "imports"
            folder.mkdir(parents=True, exist_ok=True)
            manifest = folder / ("model-supplement-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json")
            # 在写库前保存完整来源及计划插入的 ID；提交失败不会丢失审计信息。
            manifest.write_text(json.dumps(report | {"status": "prepared"}, ensure_ascii=False, indent=2), encoding="utf-8")
            if added:
                cursor.executemany("""INSERT INTO wordbook_words (book_id, id, word, meaning, pos)
                                      VALUES (5, %s, %s, %s, %s)""",
                                   [(r["id"], r["word"], r["meaning"], r["pos"]) for r in added])
            for table, before in snapshots.items():
                cursor.execute(f"SELECT * FROM {table}")
                after = cursor.fetchall()
                key = (lambda r: (r["book_id"], r["id"])) if table == "wordbook_words" else (lambda r: r["id"])
                indexed = {key(r): r for r in after}
                if any(indexed.get(key(r)) != r for r in before):
                    raise RuntimeError("已有数据发生变化，取消导入")
            cursor.execute("SELECT id, word, meaning, pos FROM wordbook_words WHERE book_id=5")
            saved = {row["id"]: row for row in cursor.fetchall()}
            if any(saved.get(r["id"]) != {k: r[k] for k in ("id", "word", "meaning", "pos")} for r in added):
                raise RuntimeError("新增词条校验失败")
        connection.commit()
        manifest.write_text(json.dumps(report | {"status": "committed"}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"added": len(added), "skipped": len(skipped), "manifest": str(manifest)}, ensure_ascii=True))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    import_supplement(parser.parse_args().source)
