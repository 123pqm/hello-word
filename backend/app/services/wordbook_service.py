"""统一词书目录和匹配范围；1 保留为原有四级，2 预留六级。"""
from app.services.cet4_service import load_cet4_vocabulary, match_vocabulary


SOURCE_URL = "https://github.com/exam-data/NETEMVocabulary"
BOOKS = {
    5: {"id": 5, "name": "补充词库", "description": "同学模型词库中现有词书未收录的词汇", "source_url": None, "license": None},
    1: {"id": 1, "name": "CET-4", "description": "大学英语四级词库", "source_url": None, "license": None},
    3: {"id": 3, "name": "考研词汇全表", "description": "2024 考纲词表，按混合考试语料词频排序",
        "source_url": SOURCE_URL, "license": "CC BY-NC-SA 4.0"},
    4: {"id": 4, "name": "考研词汇·词频优先", "description": "混合考试语料中出现至少 40 次的词条",
        "source_url": SOURCE_URL, "license": "CC BY-NC-SA 4.0"},
}


def validate_book(book_id: int) -> None:
    if book_id not in BOOKS:
        raise ValueError("该词书尚未开放")


def load_wordbook_vocabulary(database, book_id: int, selected_word_ids=None):
    validate_book(book_id)
    if book_id == 1:
        return load_cet4_vocabulary(database, selected_word_ids)
    with database.cursor() as cursor:
        sql = "SELECT id, word, meaning FROM wordbook_words WHERE book_id = %s"
        params = [book_id]
        if selected_word_ids is not None:
            if not selected_word_ids:
                raise ValueError("请至少选择一个单词")
            sql += " AND id IN (" + ",".join(["%s"] * len(selected_word_ids)) + ")"
            params.extend(selected_word_ids)
        cursor.execute(sql + " ORDER BY id", tuple(params))
        rows = cursor.fetchall()
    if selected_word_ids is not None and {row["id"] for row in rows} != set(selected_word_ids):
        raise ValueError("部分单词不属于所选词书，请返回重新选择")
    if not rows:
        raise ValueError("词书尚未导入，请先导入考研词库")
    vocabulary = {}
    for row in rows:
        word = row["word"].strip().lower()
        # May/may、March/march 在原词表分别有词条，匹配时合并释义。
        meanings = vocabulary.setdefault(word, [])
        if row["meaning"] not in meanings:
            meanings.append(row["meaning"])
    return {word: "；".join(meanings) for word, meanings in vocabulary.items()}


def match_wordbook_words(words, database, book_id: int, selected_word_ids=None):
    return match_vocabulary(words, load_wordbook_vocabulary(database, book_id, selected_word_ids))
