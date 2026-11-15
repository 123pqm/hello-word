from unittest.mock import MagicMock
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.video_status import router
from app.database import get_database
from app.auth import get_current_user_id


@pytest.fixture
def correction_client():
    app = FastAPI()
    app.include_router(router)
    db = MagicMock()
    cursor = db.cursor.return_value.__enter__.return_value
    app.dependency_overrides[get_database] = lambda: db
    app.dependency_overrides[get_current_user_id] = lambda: 7
    with TestClient(app) as client:
        yield client, db, cursor


@pytest.mark.parametrize('start,end,sentence', [
    (-1, 3, 'Hello.'), (3, 2, 'Hello.'), (3, 3, 'Hello.'),
    (0, -1, 'Hello.'), (0, 0, 'Hello.'), ('NaN', 3, 'Hello.'),
    (0, 'Infinity', 'Hello.'), (1.001, 3, 'Hello.'), (0, 3, '  '),
    (0, 3, 'a'*4001), (0, 100000000, 'Hello.'),
])
def test_invalid_correction_never_writes(correction_client, start, end, sentence):
    client, db, cursor = correction_client
    result = client.patch('/video/12/words/22', json={
        'start_time': start, 'end_time': end, 'sentence_text': sentence})
    assert result.status_code == 422
    cursor.execute.assert_not_called()
    db.commit.assert_not_called()


@pytest.mark.parametrize('method,path', [('patch', '/video/12/words/22'),
                                       ('post', '/video/12/words/22/correction')])
def test_save_uses_owner_movie_and_card_id(correction_client, method, path):
    client, db, cursor = correction_client
    cursor.fetchone.side_effect = [{'id':12, 'status':'completed'},
                                  {'id':22, 'word':'hello', 'meaning':'你好'}]
    result = getattr(client, method)(path, json={
        'start_time':0, 'end_time':2.35, 'sentence_text':' Hello there. '})
    assert result.status_code == 200
    assert result.json()['sentence_text'] == 'Hello there.'
    assert result.json()['end_time'] == 2.35
    assert cursor.execute.call_args_list[0].args[1] == (12, 7)
    assert cursor.execute.call_args_list[1].args[1] == (22, 12)
    assert cursor.execute.call_args_list[2].args[1][-2:] == (22, 12)
    db.commit.assert_called_once()


@pytest.mark.parametrize('rows,status', [([None],404),
    ([{'id':12,'status':'processing'}],409),
    ([{'id':12,'status':'completed'},None],404)])
def test_unowned_missing_or_unready_never_updates(correction_client, rows, status):
    client, db, cursor = correction_client
    cursor.fetchone.side_effect = rows
    response = client.patch('/video/12/words/22', json={
        'start_time':0,'end_time':3,'sentence_text':'Hello.'})
    assert response.status_code == status
    assert all('UPDATE movie_words' not in call.args[0] for call in cursor.execute.call_args_list)
    db.commit.assert_not_called()
    db.rollback.assert_called_once()


def test_storage_failure_rolls_back(correction_client):
    client, db, cursor = correction_client
    cursor.execute.side_effect = RuntimeError('database offline')
    response = client.patch('/video/12/words/22', json={
        'start_time':0,'end_time':3,'sentence_text':'Hello.'})
    assert response.status_code == 503
    db.rollback.assert_called_once()


def test_requires_login(correction_client):
    client, db, cursor = correction_client
    client.app.dependency_overrides.pop(get_current_user_id)
    assert client.patch('/video/12/words/22',json={
        'start_time':0,'end_time':3,'sentence_text':'Hello.'}).status_code == 401
    cursor.execute.assert_not_called()
