"""考研词库解析、分页、范围隔离及独立 MySQL 库中的导入检查。"""
import json
from unittest.mock import MagicMock

from fastapi.testclient import TestClient
import pytest

from app.auth import get_current_user_id
from app.database import connect_database, get_database, initialize_database
from app.import_netem import SOURCE_DIR, import_netem, read_netem
import app.main as main
import app.tasks.movie_task as movie_task
from app.services.wordbook_service import load_wordbook_vocabulary, match_wordbook_words


def test_pinned_source_counts_and_distinct_capitalized_entries():
    rows = read_netem()
    assert len(rows) == 5530
    assert sum(row['frequency'] >= 40 for row in rows) == 2444
    assert len({row['id'] for row in rows}) == 5530
    assert {row['word'] for row in rows if row['word'].lower() == 'may'} == {'may', 'May'}


@pytest.mark.parametrize('update', [{'序号': True}, {'序号': 0}, {'词频': -1}, {'释义': ''}, {'其他拼写': []}])
def test_invalid_source_is_rejected(tmp_path, update):
    path = tmp_path / 'bad.json'
    path.write_text(json.dumps({'words': [{'序号': 1, '词频': 10, '单词': 'word', '释义': '词'} | update]}), encoding='utf-8')
    with pytest.raises(ValueError):
        read_netem(path)


@pytest.fixture
def books_client():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    rows = [
        {'id': 1, 'word': 'the', 'meaning': '这个', 'pos': None, 'frequency': 100},
        {'id': 7, 'word': 'example', 'meaning': '例子', 'pos': None, 'frequency': 40},
        {'id': 5000, 'word': 'rare', 'meaning': '罕见的', 'pos': None, 'frequency': 2},
    ]

    def execute(sql, params=None):
        if sql.startswith('SELECT COUNT'):
            cursor.fetchone.return_value = {'total': 3 if params == (3,) else 2}
        elif sql.startswith('SELECT'):
            selected = rows if params[0] == 3 else rows[:2]
            if ' IN (' in sql:
                selected = [row for row in selected if row['id'] in params[1:]]
            if 'LIMIT' in sql:
                selected = selected[params[2]:params[2] + params[1]]
            cursor.fetchall.return_value = selected
    cursor.execute.side_effect = execute
    cursor.lastrowid = 12
    main.app.dependency_overrides[get_database] = lambda: database
    main.app.dependency_overrides[get_current_user_id] = lambda: 7
    try:
        yield TestClient(main.app), database
    finally:
        main.app.dependency_overrides.pop(get_database)
        main.app.dependency_overrides.pop(get_current_user_id)


def test_offset_pagination_does_not_skip_noncontiguous_ids(books_client):
    client, _ = books_client
    response = client.get('/wordbooks/3/words?start_index=2&end_index=3')
    assert response.status_code == 200
    assert [row['id'] for row in response.json()['data']] == [7, 5000]
    assert response.json()['total'] == 3
    assert response.json()['book']['license'] == 'CC BY-NC-SA 4.0'
    assert client.get('/wordbooks/3/words?start_index=4&end_index=5').json()['data'] == []


@pytest.mark.parametrize('path,status', [('/wordbooks/2/words', 404),
    ('/wordbooks/3/words?start_index=0', 422), ('/wordbooks/3/words?start_index=5&end_index=4', 422),
    ('/wordbooks/3/words?start_index=1&end_index=101', 422)])
def test_invalid_books_and_ranges(books_client, path, status):
    assert books_client[0].get(path).status_code == status


def test_new_wordbook_endpoint_requires_login():
    database = MagicMock()
    main.app.dependency_overrides[get_database] = lambda: database
    try:
        assert TestClient(main.app).get('/wordbooks/3/words').status_code == 401
    finally:
        main.app.dependency_overrides.pop(get_database)


def test_high_frequency_membership_checked_even_if_id_exists_in_full_book(books_client):
    _, database = books_client
    assert 'rare' in load_wordbook_vocabulary(database, 3, [5000])
    with pytest.raises(ValueError, match='不属于'):
        load_wordbook_vocabulary(database, 4, [5000])


@pytest.mark.parametrize('upload_path', ['/video/upload', '/video/web/upload'])
@pytest.mark.parametrize('book_id,mode,ids', [(3, 'all_book', None), (4, 'selected_book', '[7]')])
def test_upload_propagates_book_and_selected_ids(books_client, monkeypatch, tmp_path, book_id, mode, ids, upload_path):
    client, _ = books_client
    worker = MagicMock()
    monkeypatch.setattr(main, 'process_movie', worker)
    monkeypatch.setattr(main, 'UPLOAD_DIR', tmp_path)
    form = {'book_id': book_id, 'selection_mode': mode}
    if ids is not None:
        form['selected_word_ids'] = ids
    result = client.post(upload_path, data=form, files={'file': ('test.mp4', b'test', 'video/mp4')})
    assert result.status_code == 200, result.text
    assert worker.call_args.args[2:] == (None if ids is None else [7], book_id)


@pytest.mark.parametrize('upload_path', ['/video/upload', '/video/web/upload'])
@pytest.mark.parametrize('form', [
    {'book_id': 3, 'selection_mode': 'all_cet4'},
    {'book_id': 99, 'selection_mode': 'all_book'},
    {'book_id': 4, 'selection_mode': 'selected_book', 'selected_word_ids': '[5000]'},
    {'book_id': 4, 'selection_mode': 'selected_book', 'selected_word_ids': '[]'},
    {'book_id': 3, 'selection_mode': 'all_book', 'selected_word_ids': '[1]'},
])
def test_invalid_scope_never_starts_transcription(books_client, monkeypatch, tmp_path, form, upload_path):
    client, _ = books_client
    worker = MagicMock()
    monkeypatch.setattr(main, 'process_movie', worker)
    monkeypatch.setattr(main, 'UPLOAD_DIR', tmp_path)
    assert client.post(upload_path, data=form, files={'file': ('test.mp4', b'test', 'video/mp4')}).status_code == 422
    worker.assert_not_called()
    assert list(tmp_path.iterdir()) == []


def test_background_uses_chosen_book_and_sentence_times(books_client, monkeypatch):
    _, database = books_client
    words = [dict(word='rare', start=1, end=2),
             dict(word='example', start=3, end=4, sentence_start=2, sentence_end=5, sentence_text='An example.')]
    monkeypatch.setattr(movie_task, 'transcribe_movie', lambda _, model="tiny.en": words)
    monkeypatch.setattr(movie_task, 'connect_database', lambda: database)
    movie_task.process_movie(12, 'test.mp4', None, 4)
    assert database.cursor.return_value.__enter__.return_value.executemany.call_args.args[1] == [
        (12, 'example', '例子', 2, 5, 'An example.')]


def test_case_variants_preserve_both_meanings():
    database = MagicMock()
    database.cursor.return_value.__enter__.return_value.fetchall.return_value = [
        {'id': 1, 'word': 'may', 'meaning': '可以'}, {'id': 2, 'word': 'May', 'meaning': '五月'}]
    matches = match_wordbook_words([dict(word='MAY', start=1, end=2)], database, 3)
    assert matches[0]['meaning'] == '可以；五月'


@pytest.mark.mysql
def test_repeat_import_and_real_pagination_preserve_original_cet4(monkeypatch, mysql_test_database):
    monkeypatch.setenv('MYSQL_DATABASE', mysql_test_database)
    initialize_database()
    connection = connect_database()
    try:
        with connection.cursor() as cursor:
            cursor.execute("INSERT INTO cet4 (word, pos, meaning) VALUES ('unchanged', 'adj.', '不变的')")
            original_id = cursor.lastrowid
        connection.commit()
        assert import_netem() == {3: 5530, 4: 2444}
        assert import_netem() == {3: 5530, 4: 2444}
        connection.commit()
        with connection.cursor() as cursor:
            cursor.execute('SELECT book_id, COUNT(*) AS total FROM wordbook_words GROUP BY book_id')
            assert {row['book_id']: row['total'] for row in cursor.fetchall()} == {3: 5530, 4: 2444}
            cursor.execute("SELECT id, meaning FROM cet4 WHERE word='unchanged'")
            assert cursor.fetchone() == {'id': original_id, 'meaning': '不变的'}
        main.app.dependency_overrides[get_current_user_id] = lambda: 1
        try:
            client = TestClient(main.app)
            assert client.get('/wordbooks').json()['data'][1]['total'] == 5530
            first = client.get('/wordbooks/4/words?start_index=1&end_index=100').json()
            assert first['total'] == 2444 and len(first['data']) == 100
            assert all(row['frequency'] >= 40 for row in first['data'])
            assert len(client.get('/wordbooks/4/words?start_index=2441&end_index=2450').json()['data']) == 4
            assert client.get('/wordbooks/4/words?start_index=2445&end_index=2454').json()['data'] == []
        finally:
            main.app.dependency_overrides.pop(get_current_user_id)
    finally:
        connection.close()
