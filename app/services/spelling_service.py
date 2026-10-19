"""使用同学的 CharCNN 权重提供词书内拼写候选，模型只加载一次。"""
from functools import lru_cache
import importlib.util
from pathlib import Path
from threading import Lock

_lock = Lock()


@lru_cache(maxsize=1)
def _recognizer():
    path = Path(__file__).resolve().parents[2] / "resources/spelling_model/cet_model.py"
    spec = importlib.util.spec_from_file_location("local_spelling_model", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.load_recognizer(device="cpu")


def _distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def suggest_words(query, vocabulary):
    with _lock:
        candidates = _recognizer().recognize(query, topk=50)
    by_word = {row["word"].strip().lower(): row for row in vocabulary}
    results = []
    for item in candidates:
        word = item["word"].lower()
        # 相似度不是正确率；同时限制拼写差异，允许无可信候选时返回空列表。
        if (word not in by_word or word == query or item["score"] < 0.78
                or _distance(query, word) > max(1, len(query) // 3)):
            continue
        results.append(by_word[word] | {"score": item["score"]})
        if len(results) == 3:
            break
    return results
