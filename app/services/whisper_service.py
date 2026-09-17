from functools import lru_cache
from threading import Lock

from app.services.sentence_timing import words_with_sentence_times

_inference_lock = Lock()


@lru_cache(maxsize=1)
def _load_model():
    # 首次分析才加载模型；普通接口和离线测试不需要下载或加载 Whisper。
    import whisper

    return whisper.load_model("tiny.en")


def transcribe_movie(file_path: str):
    with _inference_lock:
        result = _load_model().transcribe(
            file_path,
            language="en",
            fp16=False,
            word_timestamps=True
        )

    return words_with_sentence_times(result["segments"])
