"""验证合并后的上传→后台处理→状态→闪卡；只写随机测试库。"""
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.database import connect_database, initialize_database
import app.main as main
import app.tasks.movie_task as movie_task


@pytest.fixture
def pipeline(monkeypatch, mysql_test_database, tmp_path):
    monkeypatch.setenv("MYSQL_DATABASE", mysql_test_database)
    initialize_database()
    word = "integration" + uuid4().hex
    database = connect_database()
    try:
        with database.cursor() as cursor:
            cursor.execute(
                "INSERT INTO cet4 (word, pos, meaning) VALUES (%s, %s, %s)",
                (word, "n.", "整合测试"),
            )
            word_id = cursor.lastrowid
        database.commit()
    finally:
        database.close()
    # 重复初始化不得重排 CET4 ID 或清空已有记录。
    initialize_database()
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    with TestClient(main.app) as client:
        account = "u" + uuid4().hex[:12]
        payload = {"account": account, "password": "DemoPass123!"}
        assert client.post("/user/sign", json=payload).status_code == 201
        login = client.post("/user/login", json=payload).json()
        client.headers["Authorization"] = "Bearer " + login["access_token"]
        client.owner_id = login["user_id"]
        yield client, word, word_id, tmp_path


@pytest.mark.mysql
def test_upload_to_flashcards_with_real_database(pipeline, monkeypatch):
    client, word, word_id, directory = pipeline
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _, model="tiny.en": [
        {"word": word, "start": 1.2, "end": 1.6, "sentence_start": 1, "sentence_end": 2,
         "sentence_text": f"It's {word}, today!"},
        {"word": word, "start": 5, "end": 6, "sentence_start": 5, "sentence_end": 7,
         "sentence_text": f"Another {word}."},
    ])
    uploaded = client.post(
        "/video/upload",
        data={"selection_mode": "selected_cet4", "selected_word_ids": json.dumps([word_id]), "user_id": "2147483647"},
        files={"file": ("电影.mp4", b"test-video", "video/mp4")},
    )
    assert uploaded.status_code == 200, uploaded.text
    movie_id = uploaded.json()["data"]["movie_id"]
    tracking_token = uploaded.json()["data"]["tracking_token"]
    tracked = client.get(f"/video/{movie_id}/progress", headers={"Authorization": "", "X-Video-Tracking": tracking_token})
    assert tracked.status_code == 200
    assert tracked.json()["data"]["status"] == "completed"
    database = connect_database()
    try:
        with database.cursor() as cursor:
            cursor.execute("SELECT user_id FROM movies WHERE id = %s", (movie_id,))
            assert cursor.fetchone()["user_id"] == client.owner_id
    finally:
        database.close()
    other = {"account": "v" + uuid4().hex[:12], "password": "DemoPass123!"}
    assert client.post("/user/sign", json=other).status_code == 201
    other_token = client.post("/user/login", json=other).json()["access_token"]
    for endpoint in ("status", "words"):
        assert client.get(f"/video/{movie_id}/{endpoint}", headers={"Authorization": "Bearer " + other_token}).status_code == 404
    status = client.get(f"/video/{movie_id}/status")
    assert status.json()["data"]["status"] == "completed"
    assert status.json()["data"]["matched_word_count"] == 1
    words = client.get(f"/video/{movie_id}/words")
    assert [{k:v for k,v in row.items() if k != 'id'} for row in words.json()["data"]["words"]] == [
        {"word": word, "meaning": "整合测试", "start_time": 1, "end_time": 2,
         "sentence_text": f"It's {word}, today!"},
    ]
    assert next(directory.iterdir()).read_bytes() == b"test-video"


@pytest.mark.mysql
def test_analysis_failure_is_visible_without_flashcards(pipeline, monkeypatch):
    client, _, _, _ = pipeline

    def fail(_, model="tiny.en"):
        raise RuntimeError("test transcription failure")

    monkeypatch.setattr(movie_task, "transcribe_movie", fail)
    uploaded = client.post(
        "/video/upload", files={"file": ("failed.mp4", b"test-video", "video/mp4")},
    )
    assert uploaded.status_code == 200
    movie_id = uploaded.json()["data"]["movie_id"]
    status = client.get(f"/video/{movie_id}/status").json()["data"]
    assert status["status"] == "failed"
    assert status["matched_word_count"] == 0
    assert client.get(f"/video/{movie_id}/words").status_code == 409


@pytest.mark.mysql
def test_delete_removes_movie_and_words_only_for_owner(pipeline, monkeypatch):
    client, word, _, _ = pipeline
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _, model="tiny.en": [
        {"word": word, "start": 1, "end": 2, "sentence_text": f"A {word}."}])
    movie_id = client.post("/video/upload", files={"file": ("delete.mp4", b"video", "video/mp4")}).json()["data"]["movie_id"]
    assert len(client.get(f"/video/{movie_id}/words").json()["data"]["words"]) == 1
    other = {"account": "d" + uuid4().hex[:12], "password": "DemoPass123!"}
    assert client.post("/user/sign", json=other).status_code == 201
    other_token = client.post("/user/login", json=other).json()["access_token"]
    assert client.delete(f"/video/{movie_id}", headers={"Authorization": "Bearer " + other_token}).status_code == 404
    assert client.get(f"/video/{movie_id}/words").status_code == 200
    assert client.delete(f"/video/{movie_id}").status_code == 204
    assert client.delete(f"/video/{movie_id}").status_code == 404
    assert client.get(f"/video/{movie_id}/words").status_code == 404
    assert movie_id not in [row["id"] for row in client.get("/video/history").json()["data"]["items"]]
    with connect_database() as db:
        with db.cursor() as cursor:
            for table in ("movies", "movie_words"):
                column = "id" if table == "movies" else "movie_id"
                cursor.execute(f"SELECT COUNT(*) AS total FROM {table} WHERE {column} = %s", (movie_id,))
                assert cursor.fetchone()["total"] == 0


@pytest.mark.mysql
def test_delete_during_transcription_cannot_recreate_words(pipeline, monkeypatch):
    client, word, _, _ = pipeline
    monkeypatch.setattr(main, "process_movie", lambda *args: None)
    movie_id = client.post("/video/upload", files={"file": ("running.mp4", b"video", "video/mp4")}).json()["data"]["movie_id"]

    def transcribe(_, model="tiny.en"):
        assert client.delete(f"/video/{movie_id}").status_code == 204
        return [{"word": word, "start": 1, "end": 2, "sentence_text": f"A {word}."}]

    monkeypatch.setattr(movie_task, "transcribe_movie", transcribe)
    movie_task.process_movie(movie_id, "running.mp4")
    with connect_database() as db:
        with db.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS total FROM movie_words WHERE movie_id = %s", (movie_id,))
            assert cursor.fetchone()["total"] == 0
    assert client.get(f"/video/{movie_id}/status").status_code == 404


@pytest.mark.mysql
def test_history_is_owned_paginated_and_excludes_legacy(pipeline):
    client, _, _, _ = pipeline
    database = connect_database()
    ids = []
    try:
        with database.cursor() as cursor:
            for name in ("first.mp4", "second.mp4", "third.mp4"):
                cursor.execute("INSERT INTO movies (user_id, file_name, status) VALUES (%s, %s, 'completed')",
                               (client.owner_id, name))
                ids.append(cursor.lastrowid)
            cursor.execute("INSERT INTO movies (user_id, file_name) VALUES (NULL, 'legacy.mp4')")
        database.commit()
    finally:
        database.close()
    response = client.get("/video/history?limit=2")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    first = response.json()["data"]
    assert [row["id"] for row in first["items"]] == [ids[2], ids[1]]
    assert first["next_before_id"] == ids[1]
    second = client.get(f"/video/history?limit=2&before_id={ids[1]}").json()["data"]
    assert [row["id"] for row in second["items"]] == [ids[0]]
    assert second["next_before_id"] is None
    assert "file_path" not in first["items"][0]
    other = {"account": "h" + uuid4().hex[:12], "password": "DemoPass123!"}
    assert client.post("/user/sign", json=other).status_code == 201
    other_token = client.post("/user/login", json=other).json()["access_token"]
    assert client.get("/video/history", headers={"Authorization": "Bearer " + other_token}).json()["data"]["items"] == []
    assert client.get("/video/history", headers={"Authorization": "Bearer invalid"}).status_code == 401
    for query in ("limit=0", "limit=101", "before_id=-1"):
        assert client.get("/video/history?" + query).status_code == 422


@pytest.mark.mysql
@pytest.mark.parametrize('enabled', [False, True])
@pytest.mark.parametrize('model_name', ['tiny.en', 'base.en'])
def test_lemmatization_upload_worker_database_pipeline(pipeline, monkeypatch, enabled, model_name):
    client, _, _, _ = pipeline
    database = connect_database()
    try:
        with database.cursor() as cursor:
            cursor.execute("INSERT IGNORE INTO cet4 (word,pos,meaning) VALUES ('work','v.','工作')")
            cursor.execute("SELECT id FROM cet4 WHERE word='work'")
            word_id = cursor.fetchone()['id']
        database.commit()
    finally:
        database.close()
    observed_models = []
    def recognize(_, model):
        observed_models.append(model)
        return [
        {'word':'worked','start':1.2,'end':1.6,'sentence_start':1,'sentence_end':3,
         'sentence_text':'They worked yesterday.'}]
    monkeypatch.setattr(movie_task, 'transcribe_movie', recognize)
    result = client.post('/video/upload', data={
        'whisper_model':model_name, 'enable_lemmatization':str(enabled).lower(), 'selection_mode':'selected_cet4',
        'selected_word_ids':json.dumps([word_id])},
        files={'file':('lemma.mp4',b'test-video','video/mp4')})
    assert result.status_code == 200
    movie_id = result.json()['data']['movie_id']
    assert observed_models == [model_name]
    cards = client.get(f'/video/{movie_id}/words').json()['data']['words']
    assert len(cards) == int(enabled)
    if enabled:
        assert cards[0]['word'] == 'work'
        assert cards[0]['sentence_text'] == 'They worked yesterday.'
        assert cards[0]['start_time'] == 1 and cards[0]['end_time'] == 3
    database = connect_database()
    try:
        with database.cursor() as cursor:
            cursor.execute('SELECT enable_lemmatization FROM movies WHERE id=%s',(movie_id,))
            assert bool(cursor.fetchone()['enable_lemmatization']) == enabled
    finally:
        database.close()
