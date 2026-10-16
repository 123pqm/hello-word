"""使用真实 SDK 和模拟 HTTP 响应，离线验证 AI 路由与错误处理。"""

import json
from pathlib import Path

from fastapi.testclient import TestClient
import httpx
from openai import AsyncOpenAI
import pytest

from app.main import app
from app.services import ai_service


PAYLOAD = {
    "word": "figure out",
    "sentence": "I can't figure out what he's getting at.",
    "previous_sentence": "He gave me a hint.",
    "next_sentence": "Could you explain it?",
}
RESULT = {
    "word": "figure out",
    "basic_meaning": "弄清楚；想明白",
    "context_meaning": "理解对方的意思",
    "sentence_translation": "我弄不明白他想表达什么。",
    "part_of_speech": "动词短语",
    "usage": "figure out 后接名词或宾语从句。",
    "collocations": ["figure out a problem：弄清一个问题"],
    "example": "We need to figure out the answer.",
    "example_translation": "我们需要想出答案。",
}


@pytest.fixture
def ai_mock(monkeypatch):
    monkeypatch.setattr(ai_service, "load_dotenv", lambda *args, **kwargs: None)
    monkeypatch.setenv("SILICONFLOW_API_KEY", "test-key-not-real")
    monkeypatch.setenv("SILICONFLOW_MODEL", "configured-test-model")
    state = {"content": json.dumps(RESULT), "status": 200, "finish": "stop", "calls": []}

    def respond(request):
        state["calls"].append(request)
        if state.get("error"):
            raise state["error"]("upstream-private-text", request=request)
        if state["status"] != 200:
            return httpx.Response(state["status"], json={"error": {"message": "upstream-private-text"}})
        choices = [] if state.get("empty") else [{
            "index": 0,
            "finish_reason": state["finish"],
            "message": {"role": "assistant", "content": state["content"]},
        }]
        return httpx.Response(200, json={
            "id": "test", "object": "chat.completion", "created": 0,
            "model": "configured-test-model", "choices": choices,
        })

    def create_client(**kwargs):
        assert kwargs["timeout"] == 30.0
        assert kwargs["max_retries"] == 0
        return AsyncOpenAI(**kwargs, http_client=httpx.AsyncClient(transport=httpx.MockTransport(respond)))

    monkeypatch.setattr(ai_service, "AsyncOpenAI", create_client)
    return state


def test_explain_word(ai_mock):
    client = TestClient(app)
    response = client.post("/ai/explain-word", json=PAYLOAD)
    assert response.status_code == 200
    assert response.json() == RESULT
    request = ai_mock["calls"][0]
    assert str(request.url) == "https://api.siliconflow.cn/v1/chat/completions"
    body = json.loads(request.content)
    assert body["model"] == "configured-test-model"
    assert json.loads(body["messages"][1]["content"]) == PAYLOAD
    assert body["temperature"] == 0.2


@pytest.mark.parametrize("changes", [{"word": " "}, {"sentence": ""}, {"word": "a" * 101}, {"next_sentence": 123}])
def test_invalid_input_does_not_call_ai(ai_mock, changes):
    response = TestClient(app).post("/ai/explain-word", json=PAYLOAD | changes)
    assert response.status_code == 422
    assert not ai_mock["calls"]


def test_optional_context_and_trim(ai_mock):
    response = TestClient(app).post("/ai/explain-word", json={"word": " figure out ", "sentence": PAYLOAD["sentence"]})
    assert response.status_code == 200
    sent = json.loads(json.loads(ai_mock["calls"][0].content)["messages"][1]["content"])
    assert sent["previous_sentence"] == sent["next_sentence"] == ""
    assert sent["word"] == "figure out"


@pytest.mark.parametrize("content", ["not json", "{}", "[]", json.dumps(RESULT | {"collocations": "wrong type"}), json.dumps(RESULT | {"word": "wrong"}), json.dumps(RESULT | {"usage": " "})])
def test_invalid_model_output(ai_mock, content):
    ai_mock["content"] = content
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).status_code == 502


def test_json_fence(ai_mock):
    ai_mock["content"] = "```json\n" + json.dumps(RESULT) + "\n```"
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).json() == RESULT


@pytest.mark.parametrize("changes", [{"finish": "length"}, {"empty": True}, {"content": None}])
def test_incomplete_output(ai_mock, changes):
    ai_mock.update(changes)
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).status_code == 502


@pytest.mark.parametrize("upstream, expected", [(401, 502), (429, 503), (500, 502)])
def test_upstream_status(ai_mock, upstream, expected):
    ai_mock["status"] = upstream
    response = TestClient(app).post("/ai/explain-word", json=PAYLOAD)
    assert response.status_code == expected
    assert "upstream-private-text" not in response.text
    assert len(ai_mock["calls"]) == 1


@pytest.mark.parametrize("error, expected", [(httpx.ReadTimeout, 504), (httpx.ConnectError, 502)])
def test_network_failure(ai_mock, error, expected):
    ai_mock["error"] = error
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).status_code == expected
    assert len(ai_mock["calls"]) == 1


def test_missing_key(ai_mock, monkeypatch):
    monkeypatch.delenv("SILICONFLOW_API_KEY")
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).status_code == 503
    assert not ai_mock["calls"]


def test_startup_and_openapi_without_ai_config(monkeypatch, ai_mock):
    import app.main as main

    initialized = []
    monkeypatch.delenv("SILICONFLOW_API_KEY")
    monkeypatch.setenv("JWT_SECRET_KEY", "test-only-key-" * 4)
    monkeypatch.setattr(main, "initialize_database", lambda: initialized.append(True))
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        operation = client.get("/openapi.json").json()["paths"]["/ai/explain-word"]["post"]
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("ExplainWordResponse")
    assert initialized == [True]


def test_root_env_from_other_working_directory(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("SILICONFLOW_API_KEY", raising=False)
    seen = []

    def load(path, **kwargs):
        seen.append(path)
        assert kwargs == {"override": False, "interpolate": False}

    monkeypatch.setattr(ai_service, "load_dotenv", load)
    assert TestClient(app).post("/ai/explain-word", json=PAYLOAD).status_code == 503
    assert seen == [ai_service.ENV_PATH]
    assert ai_service.ENV_PATH == Path(__file__).resolve().parents[1] / ".env"
