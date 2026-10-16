from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.auth import get_current_user_id
from app.database import get_database


@pytest.fixture
def profile_client():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    profile = {"id": 7, "account": "learner", "book_id": 0}
    cursor.fetchone.side_effect = lambda: dict(profile)

    def execute(sql, params=None):
        if sql.startswith("UPDATE"):
            assert params[1] == 7
            profile["book_id"] = params[0]

    cursor.execute.side_effect = execute
    app.dependency_overrides[get_database] = lambda: database
    app.dependency_overrides[get_current_user_id] = lambda: 7
    try:
        yield TestClient(app), database
    finally:
        app.dependency_overrides.pop(get_database)
        app.dependency_overrides.pop(get_current_user_id, None)


def test_profile_and_persistent_selection(profile_client):
    client, database = profile_client
    initial = client.get('/user/me')
    assert initial.json()['data'] == dict(user_id=7, account='learner', book_id=0, book=None)
    assert initial.headers['Cache-Control'] == 'no-store'
    for _ in range(2):
        result = client.put('/user/me/wordbook', json={'book_id': 3})
        assert result.status_code == 200
        assert result.json()['data']['book']['id'] == 3
    assert client.get('/user/me').json()['data']['book_id'] == 3
    assert database.commit.call_count == 2


@pytest.mark.parametrize('body', [{'book_id': 2}, {'book_id': 0}, {'book_id': True},
                                  {'book_id': '3'}, {'book_id': 3, 'id': 8}])
def test_invalid_selection_never_writes(profile_client, body):
    client, database = profile_client
    assert client.put('/user/me/wordbook', json=body).status_code == 422
    database.commit.assert_not_called()


def test_legacy_selection_rejects_other_user(profile_client):
    client, database = profile_client
    assert client.put('/user/chose', json={'id': 8, 'book_id': 3}).status_code == 403
    database.commit.assert_not_called()
    assert client.put('/user/chose', json={'id': 7, 'book_id': 3}).json() == {'reply': '成功更改单词书'}


def test_failed_write_rolls_back(profile_client):
    client, database = profile_client
    database.commit.side_effect = RuntimeError('internal database details')
    result = client.put('/user/me/wordbook', json={'book_id': 1})
    assert result.status_code == 503
    assert 'internal' not in result.text
    database.rollback.assert_called_once()


@pytest.mark.parametrize('method,path,body', [('get','/user/me',None),
    ('put','/user/me/wordbook',{'book_id': 1}), ('put','/user/chose',{'id': 7,'book_id': 1})])
def test_profile_requires_real_auth(profile_client, method, path, body):
    client, database = profile_client
    app.dependency_overrides.pop(get_current_user_id)
    assert client.request(method, path, json=body).status_code == 401
    database.commit.assert_not_called()
