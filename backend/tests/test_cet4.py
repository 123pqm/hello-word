"""CET4 的 Swagger 认证声明及请求参数回归测试，不连接业务数据库。"""

from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.database import get_database
from app.main import app


@pytest.fixture
def cet4_client():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    cursor.fetchall.return_value = [
        {"id": 1, "word": "Africa", "meaning": "非洲", "pos": "n."}
    ]
    app.dependency_overrides[get_database] = lambda: database
    client = TestClient(app)
    try:
        yield client, cursor
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


def test_swagger_uses_security_instead_of_authorization_parameter():
    schema = app.openapi()
    operation = schema["paths"]["/user/chose/cet4"]["get"]
    assert "requestBody" not in operation
    assert {(p["in"], p["name"]) for p in operation["parameters"]} == {
        ("query", "start_index"), ("query", "end_index"), ("query", "book_id")
    }
    assert operation["security"] == [{"AuthorizationToken": []}]
    assert schema["components"]["securitySchemes"]["AuthorizationToken"]["name"] == "Authorization"


@pytest.mark.parametrize("token", ["test-token", "Bearer test-token"])
def test_cet4_accepts_query_and_authorization_without_body(cet4_client, token):
    client, cursor = cet4_client
    response = client.get(
        "/user/chose/cet4",
        params={"start_index": 1, "end_index": 15, "book_id": 1},
        headers={"Authorization": token},
    )
    assert response.status_code == 200
    assert response.json()["data"][0]["pos"] == "n."
    assert cursor.execute.call_args.args[1] == (1, 15)


def test_missing_authorization_is_authentication_error(cet4_client):
    client, cursor = cet4_client
    response = client.get("/user/chose/cet4?start_index=1&end_index=15&book_id=1")
    assert response.status_code == 401
    cursor.execute.assert_not_called()


def test_invalid_query_still_reports_exact_field(cet4_client):
    client, cursor = cet4_client
    response = client.get(
        "/user/chose/cet4?start_index=abc&end_index=15&book_id=1",
        headers={"Authorization": "test-token"},
    )
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["query", "start_index"]
    cursor.execute.assert_not_called()
