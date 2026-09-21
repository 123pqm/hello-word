from datetime import datetime, timezone
from unittest.mock import MagicMock

import jwt
import pytest
from fastapi.testclient import TestClient

from app.database import get_database
from app.main import app, create_access_token


@pytest.fixture
def auth_client(monkeypatch):
    monkeypatch.setenv("JWT_SECRET_KEY", "test-key-" * 8)
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    cursor.fetchone.return_value = {"id": 7}
    app.dependency_overrides[get_database] = lambda: database
    client = TestClient(app, raise_server_exceptions=False)
    try:
        yield client, cursor, database
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


@pytest.mark.parametrize("kind", ["missing", "garbage", "wrong-key", "expired", "missing-exp", "wrong-algorithm", "bad-sub"])
def test_invalid_auth_cannot_upload_or_read(auth_client, kind):
    client, cursor, database = auth_client
    now = int(datetime.now(timezone.utc).timestamp())
    claims = {"sub": "7", "iat": now, "exp": now + 1800}
    if kind == "expired": claims["exp"] = now - 1
    if kind == "missing-exp": claims.pop("exp")
    if kind == "bad-sub": claims["sub"] = "-1"
    token = jwt.encode(claims, "wrong-key-" * 8 if kind == "wrong-key" else "test-key-" * 8,
                       algorithm="HS384" if kind == "wrong-algorithm" else "HS256")
    headers = {} if kind == "missing" else {"Authorization": "Bearer " + ("garbage" if kind == "garbage" else token)}
    for path in ("/video/17/status", "/video/17/words", "/video/history", "/video/17/cover"):
        assert client.get(path, headers=headers).status_code == 401
    assert client.post("/video/upload", headers=headers,
                       files={"file": ("test.mp4", b"test", "video/mp4")}).status_code == 401
    database.commit.assert_not_called()


def test_deleted_user_and_unowned_movies_are_inaccessible(auth_client):
    client, cursor, _ = auth_client
    headers = {"Authorization": "Bearer " + create_access_token(7)}
    cursor.fetchone.return_value = None
    assert client.get("/video/17/status", headers=headers).status_code == 401
    for endpoint in ("status", "words"):
        cursor.fetchone.side_effect = [{"id": 7}, None]
        assert client.get(f"/video/17/{endpoint}", headers=headers).status_code == 404
        sql, values = cursor.execute.call_args.args
        assert "AND user_id = %s" in sql
        assert values == (17, 7)
