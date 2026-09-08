from fastapi.testclient import TestClient
import pytest

from app.main import app


@pytest.mark.mysql
def test_startup_and_default_database_dependency(monkeypatch, mysql_test_database, mysql_database):
    monkeypatch.setenv("MYSQL_DATABASE", mysql_test_database)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/words/1").json()["word"] == "experience"
