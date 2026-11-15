import sys
from types import SimpleNamespace
from unittest.mock import MagicMock
import pytest
import app.services.whisper_service as service


def test_single_model_cache_switches_and_preserves_transcription_options(monkeypatch):
    loader=MagicMock(side_effect=lambda *a,**k: MagicMock(**{'transcribe.return_value':{'segments':[]}}))
    monkeypatch.setitem(sys.modules,'whisper',SimpleNamespace(load_model=loader))
    monkeypatch.setattr(service,'_model',None)
    monkeypatch.setattr(service,'_model_name',None)
    for name in ['tiny.en','tiny.en','base.en','tiny.en']:
        assert service.transcribe_movie('sample.mp4',name) == []
        service._model.transcribe.assert_called_with('sample.mp4',language='en',fp16=False,word_timestamps=True)
    assert [call.args[0] for call in loader.call_args_list] == ['tiny.en','base.en','tiny.en']
    with pytest.raises(ValueError):
        service.transcribe_movie('sample.mp4','large')
