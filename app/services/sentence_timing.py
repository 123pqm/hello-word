"""从 Whisper 逐词结果保留原句和句子范围，原词时间用于首次出现排序。"""

import re


_ABBREVIATIONS = {"mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.", "e.g.", "i.e."}


def _ends_sentence(text: str) -> bool:
    text = text.strip().rstrip("\"'”’)]}")
    if text.lower() in _ABBREVIATIONS or re.fullmatch(r"(?:[A-Za-z]\.){2,}", text):
        return False
    return text.endswith((".", "?", "!", "…"))


def words_with_sentence_times(segments: list[dict]) -> list[dict]:
    """句子可跨识别 segment；无标点时按 2 秒停顿或 30 秒片段兜底。"""
    words = []
    sentence = []
    sentence_tokens = []
    sentence_start = None
    sentence_end = None

    def flush():
        nonlocal sentence_start, sentence_end
        # 使用原始 token 拼句，不能用已转为小写、去除标点的匹配词。
        text = " ".join(sentence_tokens)
        text = re.sub(r"\s+([.,!?;:%…\)\]\}])", r"\1", text)
        text = re.sub(r"([\(\[\{])\s+", r"\1", text)
        text = re.sub(r"\s+(['’](?:s|t|re|ve|ll|d|m)\b)", r"\1", text)
        for word in sentence:
            word["sentence_start"] = round(sentence_start, 2)
            word["sentence_end"] = round(sentence_end, 2)
            word["sentence_text"] = text
        words.extend(sentence)
        sentence.clear()
        sentence_tokens.clear()
        sentence_start = sentence_end = None

    for segment in segments:
        for item in segment.get("words", []):
            raw_word = item["word"].strip()
            if not raw_word:
                continue
            start, end = float(item["start"]), float(item["end"])
            if sentence_tokens and (start - sentence_end >= 2.0 or end - sentence_start > 30.0):
                flush()
            if sentence_start is None:
                sentence_start = start
            sentence_end = end if sentence_end is None else max(sentence_end, end)
            # 数字和独立标点也属于原句，即使它们不参加词库匹配。
            sentence_tokens.append(raw_word)
            clean_word = re.sub(r"^[^a-zA-Z]+|[^a-zA-Z]+$", "", raw_word).lower()
            if clean_word:
                sentence.append({
                    "word": clean_word,
                    "start": round(start, 2),
                    "end": round(end, 2),
                })
            # 标点可能是独立 token；即使不是词也需要结束上一句。
            if _ends_sentence(raw_word):
                flush()
    flush()
    return words
