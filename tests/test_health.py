from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_health() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "movie-vocab-api",
        "version": "0.1.0",
         "isok" : "no"
    }


def test_not_found() -> None:
    response = client.get("/not-found")

    assert response.status_code == 404
