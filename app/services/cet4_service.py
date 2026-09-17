"""将识别出的单词与数据库中的四级词库匹配。"""

from pymysql.connections import Connection


def load_cet4_vocabulary(
    database: Connection, selected_word_ids: list[int] | None = None
) -> dict[str, str]:
    """None 表示整本词库；指定 ID 时必须全部存在，不能回退为整本词库。"""
    with database.cursor() as cursor:
        if selected_word_ids is None:
            cursor.execute("SELECT word, meaning FROM cet4")
        else:
            if not selected_word_ids:
                raise ValueError("请至少选择一个四级单词")
            placeholders = ",".join(["%s"] * len(selected_word_ids))
            cursor.execute(
                f"SELECT id, word, meaning FROM cet4 WHERE id IN ({placeholders})",
                tuple(selected_word_ids),
            )
        rows = cursor.fetchall()
        if selected_word_ids is not None and {row["id"] for row in rows} != set(selected_word_ids):
            raise ValueError("部分选中单词已不在四级词库中，请返回重新选择")
        vocabulary = {
            row["word"].strip().lower(): row["meaning"]
            for row in rows
        }

    if not vocabulary:
        raise ValueError("四级词库为空，请先导入 cet4 词库")
    return vocabulary


def match_cet4_words(
    words: list[dict], database: Connection, selected_word_ids: list[int] | None = None
) -> list[dict]:
    """按词出现时间去重；返回首次出现所在句子的播放范围。"""
    vocabulary = load_cet4_vocabulary(database, selected_word_ids)

    matched = []
    seen = set()
    for item in sorted(words, key=lambda item: item["start"]):
        word = item["word"].strip().lower()
        if word in vocabulary and word not in seen:
            seen.add(word)
            matched.append({
                "word": word,
                "meaning": vocabulary[word],
                "start": item.get("sentence_start", item["start"]),
                "end": item.get("sentence_end", item["end"]),
            })
    return matched
