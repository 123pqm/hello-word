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
        yield client, word, word_id, tmp_path


@pytest.mark.mysql
def test_upload_to_flashcards_with_real_database(pipeline, monkeypatch):
    client, word, word_id, directory = pipeline
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _: [
        {"word": word, "start": 1.2, "end": 1.6, "sentence_start": 1, "sentence_end": 2},
        {"word": word, "start": 5, "end": 6, "sentence_start": 5, "sentence_end": 7},
    ])
    uploaded = client.post(
        "/video/upload",
        data={"selection_mode": "selected_cet4", "selected_word_ids": json.dumps([word_id])},
        files={"file": ("电影.mp4", b"test-video", "video/mp4")},
    )
    assert uploaded.status_code == 200, uploaded.text
    movie_id = uploaded.json()["data"]["movie_id"]
    status = client.get(f"/video/{movie_id}/status")
    assert status.json()["data"]["status"] == "completed"
    assert status.json()["data"]["matched_word_count"] == 1
    words = client.get(f"/video/{movie_id}/words")
    assert words.json()["data"]["words"] == [
        {"word": word, "meaning": "整合测试", "start_time": 1, "end_time": 2},
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
