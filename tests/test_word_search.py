from unittest.mock import MagicMock
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.wordbooks import router
from app.database import get_database
from app.auth import get_current_user_id

@pytest.fixture
def search_client():
    db = MagicMock()
    cursor = db.cursor.return_value.__enter__.return_value
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_database] = lambda: db
    app.dependency_overrides[get_current_user_id] = lambda: 7
    with TestClient(app) as client:
        yield client, cursor

@pytest.mark.parametrize("book", [1, 3, 4])
def test_page_and_scope(search_client, book):
    client, cursor = search_client
    cursor.fetchall.return_value = [dict(id=i, word="test", meaning="测试", pos=None) for i in range(3)]
    response = client.get(f"/wordbooks/{book}/search", params={"q": " Test ", "limit": 2, "offset": 2})
    assert response.status_code == 200
    data = response.json()
    assert len(data["data"]) == 2 and data["has_more"] and data["next_offset"] == 4
    assert data["query"] == "test"
    sql, params = cursor.execute.call_args.args
    assert params[-2:] == (3, 2)
    assert ("book_id = %s" in sql) == (book != 1)
    if book != 1:
        assert params[0] == book
    assert "LOWER(word)" in sql
    cursor.fetchall.return_value = []
    data = client.get(f"/wordbooks/{book}/search?q=missing").json()
    assert data["data"] == [] and data["next_offset"] is None and not data["has_more"]

@pytest.mark.parametrize("query", ["q=", "q=+++", "q=x&limit=101", "q=x&offset=-1", "q=" + "a"*81])
def test_invalid_inputs(search_client, query):
    client, cursor = search_client
    assert client.get("/wordbooks/3/search?" + query).status_code == 422
    cursor.execute.assert_not_called()

def test_literal_wildcards_and_no_sql_interpolation(search_client):
    client, cursor = search_client
    cursor.fetchall.return_value = []
    assert client.get("/wordbooks/3/search", params={"q": "a%_!'"}).status_code == 200
    sql, params = cursor.execute.call_args.args
    assert params[1] == "%a!%!_!!'%"
    assert "a%_!'" not in sql

def test_auth_and_missing_book(search_client):
    client, cursor = search_client
    assert client.get("/wordbooks/2/search?q=a").status_code == 404
    del client.app.dependency_overrides[get_current_user_id]
    assert client.get("/wordbooks/3/search?q=a").status_code == 401
    cursor.execute.assert_not_called()
