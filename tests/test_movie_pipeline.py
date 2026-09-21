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
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _: [
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
    assert words.json()["data"]["words"] == [
        {"word": word, "meaning": "整合测试", "start_time": 1, "end_time": 2,
         "sentence_text": f"It's {word}, today!"},
    ]
    assert next(directory.iterdir()).read_bytes() == b"test-video"


@pytest.mark.mysql
def test_analysis_failure_is_visible_without_flashcards(pipeline, monkeypatch):
    client, _, _, _ = pipeline

    def fail(_):
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
