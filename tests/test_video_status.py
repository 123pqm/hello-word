from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.database import get_database
from app.main import app


@pytest.fixture
def status_client():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    app.dependency_overrides[get_database] = lambda: database
    client = TestClient(app)
    try:
        yield client, cursor, database
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


@pytest.mark.parametrize("status", ["pending", "processing", "failed"])
def test_nonterminal_and_failed_status(status_client, status):
    client, cursor, database = status_client
    cursor.fetchone.return_value = {"id": 17, "status": status}
    response = client.get("/video/17/status")
    assert response.status_code == 200
    assert response.json()["data"]["status"] == status
    assert response.json()["data"]["movie_id"] == 17
    assert response.headers["cache-control"] == "no-store"
    cursor.execute.assert_called_once()
    assert cursor.execute.call_args.args[1] == (17,)
    database.commit.assert_not_called()


@pytest.mark.parametrize("count", [0, 5])
def test_completed_including_no_matching_words(status_client, count):
    client, cursor, _ = status_client
    cursor.fetchone.side_effect = [{"id": 17, "status": "completed"}, {"total": count}]
    response = client.get("/video/17/status")
    assert response.status_code == 200
    assert response.json()["data"]["matched_word_count"] == count


def test_missing_job(status_client):
    client, cursor, _ = status_client
    cursor.fetchone.return_value = None
    assert client.get("/video/17/status").status_code == 404


@pytest.mark.parametrize("value", ["0", "-1", "bad"])
def test_invalid_id(status_client, value):
    client, cursor, _ = status_client
    assert client.get(f"/video/{value}/status").status_code == 422
    cursor.execute.assert_not_called()


def test_database_failure_is_not_completion(status_client):
    client, cursor, _ = status_client
    cursor.execute.side_effect = RuntimeError("private database details")
    response = client.get("/video/17/status")
    assert response.status_code == 503
    assert "private database details" not in response.text


def test_flashcards_return_only_requested_movie_words(status_client):
    client, cursor, database = status_client
    cursor.fetchone.return_value = {"id": 17, "status": "completed"}
    cursor.fetchall.return_value = [
        {"word": "movie", "meaning": "电影", "start_time": 1.25, "end_time": 1.8},
        {"word": "world", "meaning": "世界", "start_time": 7.0, "end_time": 7.5},
    ]
    response = client.get("/video/17/words")
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["movie_id"] == 17
    assert data["words"] == cursor.fetchall.return_value
    query, parameters = cursor.execute.call_args.args
    assert "WHERE movie_id = %s" in query
    assert "ORDER BY start_time" in query
    assert parameters == (17,)
    assert response.headers["cache-control"] == "no-store"
    database.commit.assert_not_called()


def test_completed_movie_can_have_empty_flashcards(status_client):
    client, cursor, _ = status_client
    cursor.fetchone.return_value = {"id": 17, "status": "completed"}
    cursor.fetchall.return_value = []
    assert client.get("/video/17/words").json()["data"]["words"] == []


@pytest.mark.parametrize("status", ["pending", "processing", "failed"])
def test_flashcards_do_not_read_partial_or_failed_results(status_client, status):
    client, cursor, _ = status_client
    cursor.fetchone.return_value = {"id": 17, "status": status}
    assert client.get("/video/17/words").status_code == 409
    cursor.fetchall.assert_not_called()


def test_missing_flashcard_movie(status_client):
    client, cursor, _ = status_client
    cursor.fetchone.return_value = None
    assert client.get("/video/17/words").status_code == 404


@pytest.mark.parametrize("value", ["0", "-1", "bad"])
def test_invalid_flashcard_movie_id(status_client, value):
    client, cursor, _ = status_client
    assert client.get(f"/video/{value}/words").status_code == 422
    cursor.execute.assert_not_called()


def test_flashcard_database_failure_can_be_retried(status_client):
    client, cursor, _ = status_client
    cursor.execute.side_effect = RuntimeError("private database details")
    response = client.get("/video/17/words")
    assert response.status_code == 503
    assert "private database details" not in response.text
