from unittest.mock import MagicMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.auth import get_current_user_id
from app.database import get_database
from app.services import movie_cover
from app.video_status import router


@pytest.fixture
def movie_client(tmp_path, monkeypatch):
    monkeypatch.setattr(movie_cover, "VIDEO_DIR", tmp_path)
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_database] = lambda: database
    app.dependency_overrides[get_current_user_id] = lambda: 7
    with TestClient(app) as client:
        yield client, cursor, tmp_path


def test_file_supports_authenticated_range_and_migrated_paths(movie_client):
    client, cursor, root = movie_client
    (root / "clip.mp4").write_bytes(b"0123456789")
    cursor.fetchone.return_value = {"file_path": r"C:\old\uploads\videos\clip.mp4"}
    response = client.get("/video/26/file", headers={"Range": "bytes=2-5"})
    assert response.status_code == 206
    assert response.content == b"2345"
    assert response.headers["content-range"] == "bytes 2-5/10"
    assert response.headers["cache-control"] == "private, no-store"
    cursor.execute.assert_called_once_with(
        "SELECT file_path FROM movies WHERE id = %s AND user_id = %s", (26, 7)
    )


@pytest.mark.parametrize("row", [None, {"file_path": "missing.mp4"}, {"file_path": "private.env"}, {"file_path": ".."}])
def test_missing_or_disallowed_file_returns_404(movie_client, row):
    client, cursor, root = movie_client
    (root / "private.env").write_text("not a video")
    cursor.fetchone.return_value = row
    assert client.get("/video/26/file").status_code == 404


def test_anonymous_cannot_read_file(movie_client):
    client, cursor, _ = movie_client
    del client.app.dependency_overrides[get_current_user_id]
    assert client.get("/video/26/file").status_code == 401
    cursor.execute.assert_not_called()


def test_playback_ticket_range_scope_expiry_and_logout(movie_client, monkeypatch):
    import jwt
    import time
    client, cursor, root = movie_client
    key = "playback-test-secret-" * 3
    monkeypatch.setenv("JWT_SECRET_KEY", key)
    (root / "clip.mp4").write_bytes(b"0123456789")
    cursor.fetchone.return_value = {"id": 7, "file_path": "clip.mp4"}
    token = jwt.encode({"sub": "7", "iat": int(time.time()), "exp": int(time.time()) + 600}, key, algorithm="HS256")
    response = client.post("/video/26/playback", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    ticket = response.json()["data"]["ticket"]
    assert ticket != token
    url = f"/video/26/stream?ticket={ticket}"
    part = client.get(url, headers={"Range": "bytes=2-5"})
    assert part.status_code == 206
    assert part.content == b"2345"
    assert part.headers["content-range"] == "bytes 2-5/10"
    assert client.get(url, headers={"Range": "bytes=999-"}).status_code == 416
    assert client.get(f"/video/27/stream?ticket={ticket}").status_code == 401
    assert client.get(f"/video/26/stream?ticket={token}").status_code == 401
    claims = jwt.decode(ticket, key, algorithms=["HS256"], audience="movie-playback")
    claims["exp"] = int(time.time()) - 1
    expired = jwt.encode(claims, key, algorithm="HS256")
    assert client.get(f"/video/26/stream?ticket={expired}").status_code == 401
    cursor.fetchone.return_value = None
    assert client.get(url).status_code == 401


def test_playback_requires_login(movie_client):
    client, cursor, _ = movie_client
    del client.app.dependency_overrides[get_current_user_id]
    assert client.post("/video/26/playback").status_code == 401
    cursor.execute.assert_not_called()
