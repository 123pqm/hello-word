from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.auth import get_current_user_id
from app.database import get_database
from app.main import app
import app.video_status as video_status


@pytest.fixture
def status_client():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    app.dependency_overrides[get_database] = lambda: database
    app.dependency_overrides[get_current_user_id] = lambda: 7
    client = TestClient(app)
    try:
        yield client, cursor, database
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)
        app.dependency_overrides.pop(get_current_user_id, None)


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
    assert cursor.execute.call_args.args[1] == (17, 7)
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
        {"word": "movie", "meaning": "鐢靛奖", "start_time": 1.25, "end_time": 1.8},
        {"word": "world", "meaning": "涓栫晫", "start_time": 7.0, "end_time": 7.5},
    ]
    response = client.get("/video/17/words")
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["movie_id"] == 17
    assert data["words"] == [dict(row, sentence_text=None) for row in cursor.fetchall.return_value]
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


def test_cover_checks_owner_before_reading_file(status_client, monkeypatch, tmp_path):
    client, cursor, _ = status_client
    cover = tmp_path / "cover.jpg"
    cover.write_bytes(b"\xff\xd8test")
    cursor.fetchone.return_value = {"file_path": "uploads/videos/demo.mp4"}
    monkeypatch.setattr(video_status, "get_cover", lambda path: cover)
    response = client.get("/video/17/cover")
    assert response.status_code == 200
    assert response.headers["content-type"] == "image/jpeg"
    assert response.content == cover.read_bytes()
    assert cursor.execute.call_args.args[1] == (17, 7)
    assert "AND user_id = %s" in cursor.execute.call_args.args[0]
    cursor.fetchone.return_value = None
    monkeypatch.setattr(video_status, "get_cover", lambda path: pytest.fail("不能读取他人电影"))
    assert client.get("/video/17/cover").status_code == 404
