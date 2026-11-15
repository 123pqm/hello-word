from unittest.mock import MagicMock
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.auth import get_current_user_id
from app.database import get_database
from app.wordbooks import router
import app.wordbooks as routes
import app.services.spelling_service as service


def test_filters_wrong_book_low_score_and_distant_spelling(monkeypatch):
    model = MagicMock()
    model.recognize.return_value = [
        {"word": "insecure", "score": .89},
        {"word": "insurance", "score": .85},
        {"word": "insure", "score": .70},
        {"word": "banana", "score": .99},
    ]
    monkeypatch.setattr(service, "_recognizer", lambda: model)
    rows = [{"id": i, "word": w, "meaning": "数据库释义"}
            for i, w in enumerate(["insurance", "insure", "banana"])]
    assert [x["word"] for x in service.suggest_words("insurence", rows)] == ["insurance"]
    assert service.suggest_words("zzzzzz", rows) == []


@pytest.mark.parametrize("book", [1, 3, 4, 5])
def test_route_scope_auth_validation_and_failure(monkeypatch, book):
    app = FastAPI()
    app.include_router(router)
    db = MagicMock()
    cursor = db.cursor.return_value.__enter__.return_value
    rows = [{"id": 9, "word": "abandon", "meaning": "放弃", "pos": "v"}]
    cursor.fetchall.return_value = rows
    app.dependency_overrides[get_database] = lambda: db
    app.dependency_overrides[get_current_user_id] = lambda: 1
    suggest = MagicMock(return_value=rows)
    monkeypatch.setattr(routes, "suggest_words", suggest)
    with TestClient(app) as client:
        response = client.get(f"/wordbooks/{book}/suggestions?q=Abandn")
        assert response.status_code == 200
        assert response.json()["data"] == rows
        suggest.assert_called_once_with("abandn", rows)
        sql, *params = cursor.execute.call_args.args
        assert ("FROM cet4" in sql) == (book == 1)
        if book != 1:
            assert params == [(book,)]
        suggest.reset_mock()
        assert client.get(f"/wordbooks/{book}/suggestions?q=123").json()["data"] == []
        suggest.assert_not_called()
        suggest.side_effect = RuntimeError("unavailable")
        assert client.get(f"/wordbooks/{book}/suggestions?q=abandn").status_code == 503
        assert client.get("/wordbooks/2/suggestions?q=abandn").status_code == 404
        app.dependency_overrides.pop(get_current_user_id)
        assert client.get(f"/wordbooks/{book}/suggestions?q=abandn").status_code == 401
