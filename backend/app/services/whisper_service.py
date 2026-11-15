import gc
from threading import Lock

from app.services.sentence_timing import words_with_sentence_times

_inference_lock = Lock()
_model = None
_model_name = None


def _load_model(model_name: str = "tiny.en"):
    global _model, _model_name
    if model_name not in {"tiny.en", "base.en"}:
        raise ValueError("不支持的语音识别模型")
    # 首次分析才加载模型；普通接口和离线测试不需要下载或加载 Whisper。
    import whisper

    if _model_name != model_name or _model is None:
        _model = None
        _model_name = None
        gc.collect()
        _model = whisper.load_model(model_name, device="cpu")
        _model_name = model_name
    return _model


def transcribe_movie(file_path: str, model_name: str = "tiny.en"):
    with _inference_lock:
        result = _load_model(model_name).transcribe(
            file_path,
            language="en",
            fp16=False,
            word_timestamps=True
        )

    return words_with_sentence_times(result["segments"])
