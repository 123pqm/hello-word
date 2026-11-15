from datetime import datetime, timedelta, timezone
import os
from unittest.mock import MagicMock

import jwt
import pytest
from fastapi.testclient import TestClient

from app.database import get_database
from app.main import app, create_access_token
from app.video_tracking import create_tracking_token, AUDIENCE


@pytest.fixture
def tracking_client():
    db = MagicMock()
    cursor = db.cursor.return_value.__enter__.return_value
    app.dependency_overrides[get_database] = lambda: db
    client = TestClient(app, raise_server_exceptions=True)
    try:
        yield client, cursor
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


def test_tracking_works_without_login_and_checks_movie_owner(tracking_client):
    client, cursor = tracking_client
    cursor.fetchone.side_effect = [{"id": 17, "status": "completed"}, {"total": 5}]
    token = create_tracking_token(7, 17)
    response = client.get("/video/17/progress", headers={"X-Video-Tracking": token})
    assert response.status_code == 200
    assert response.json()["data"]["matched_word_count"] == 5
    assert cursor.execute.call_args_list[0].args[1] == (17, 7)
    assert response.headers["cache-control"] == "no-store"
    assert client.get("/video/18/progress", headers={"X-Video-Tracking": token}).status_code == 401
    cursor.fetchone.side_effect = None
    cursor.fetchone.return_value = None
    assert client.get("/video/17/progress", headers={"X-Video-Tracking": token}).status_code == 404


@pytest.mark.parametrize("endpoint", ["/video/17/words", "/video/17/file", "/video/history", "/favorites/words"])
def test_tracking_token_cannot_be_used_as_login(tracking_client, endpoint):
    client, cursor = tracking_client
    assert client.get(endpoint, headers={"Authorization": "Bearer " + create_tracking_token(7, 17)}).status_code == 401
    cursor.execute.assert_not_called()


def test_tracking_rejects_expired_missing_forged_and_access_tokens(tracking_client):
    client, cursor = tracking_client
    now = datetime.now(timezone.utc)
    expired = jwt.encode({"sub": "7", "movie_id": 17, "aud": AUDIENCE,
                          "iat": now - timedelta(days=2), "exp": now - timedelta(days=1)},
                         os.environ["JWT_SECRET_KEY"], algorithm="HS256")
    for token in ("", "forged", expired, create_access_token(7)):
        assert client.get("/video/17/progress", headers={"X-Video-Tracking": token}).status_code == 401
    cursor.execute.assert_not_called()
