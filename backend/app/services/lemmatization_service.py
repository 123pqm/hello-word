"""按完整句子分析词形，保留 Whisper 原词、原句与所有时间。"""
from functools import lru_cache
from threading import Lock
import re

_lock = Lock()


@lru_cache(maxsize=1)
def _pipeline():
    import spacy
    # 保留词性标注和词形还原；不需要句法树和实体识别。
    return spacy.load("en_core_web_sm", exclude=["parser", "ner"])


def lemmatize_words(words: list[dict]) -> list[dict]:
    if not words:
        return []
    result = [dict(word) for word in words]
    groups = {}
    for item in result:
        sentence = item.get("sentence_text")
        if not sentence:
            continue  # 缺少上下文时保留原词，避免误还原。
        key = (item.get("sentence_start"), item.get("sentence_end"), sentence)
        groups.setdefault(key, []).append(item)
    texts = list(dict.fromkeys(key[2] for key in groups))
    with _lock:
        docs = dict(zip(texts, _pipeline().pipe(texts, batch_size=64)))
    for key, items in groups.items():
        doc = docs[key[2]]
        # 按原句字符位置找词，不用全局 word→lemma 字典；同词可在不同语境有不同原形。
        position = 0
        for item in items:
            original = item["word"].strip().lower()
            match = re.search(r"(?<![a-zA-Z])" + re.escape(original) + r"(?![a-zA-Z])",
                              key[2][position:], flags=re.IGNORECASE)
            if match is None:
                continue
            start, end = position + match.start(), position + match.end()
            position = end
            tokens = [token for token in doc if token.idx == start and token.idx + len(token.text) == end]
            if len(tokens) == 1 and tokens[0].pos_ in {"NOUN", "VERB", "AUX", "ADJ", "ADV"}:
                lemma = tokens[0].lemma_.lower()
                if re.fullmatch(r"[a-z]+(?:[-'][a-z]+)*", lemma):
                    item["lemma"] = lemma
    return result
