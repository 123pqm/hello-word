"""匹配范围的接口和数据库筛选测试；不写真实数据库、不运行 Whisper。"""

from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

import app.main as main
from app.services.cet4_service import match_cet4_words
import app.tasks.movie_task as movie_task


@pytest.fixture
def selection_database():
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    cursor.lastrowid = 17
    rows = [
        {"id": 1, "word": "Africa", "meaning": "非洲"},
        {"id": 2, "word": "African", "meaning": "非洲的"},
    ]

    def execute(sql, params=None):
        if sql.startswith("SELECT"):
            cursor.fetchall.return_value = rows if params is None else [row for row in rows if row["id"] in params]

    cursor.execute.side_effect = execute
    return database


@pytest.mark.parametrize("form, expected_ids", [
    ({}, None),
    ({"selection_mode": "all_cet4"}, None),
    ({"selection_mode": "selected_cet4", "selected_word_ids": "[2, 2]"}, [2]),
])
def test_upload_passes_scope_to_background(monkeypatch, tmp_path, selection_database, form, expected_ids):
    worker = MagicMock()
    monkeypatch.setattr(main, "process_movie", worker)
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    main.app.dependency_overrides[main.get_database] = lambda: selection_database
    try:
        # 不启动 lifespan，避免初始化用户数据库。
        client = TestClient(main.app)
        response = client.post("/video/upload", data=form, files={"file": ("demo.mp4", b"video", "video/mp4")})
        client.close()
        assert response.status_code == 200, response.text
        assert response.json()["data"]["status"] == "pending"
        assert worker.call_args.args[0] == 17
        assert worker.call_args.args[2] == expected_ids
        selection_database.commit.assert_called_once()
    finally:
        main.app.dependency_overrides.pop(main.get_database)


@pytest.mark.parametrize("ids", [None, "[]", "bad json", "[true]", '["1"]', "[0]", "[999]"])
def test_bad_selected_scope_cannot_fall_back_to_whole_book(monkeypatch, tmp_path, selection_database, ids):
    worker = MagicMock()
    monkeypatch.setattr(main, "process_movie", worker)
    monkeypatch.setattr(main, "UPLOAD_DIR", tmp_path)
    main.app.dependency_overrides[main.get_database] = lambda: selection_database
    try:
        form = {"selection_mode": "selected_cet4"}
        if ids is not None:
            form["selected_word_ids"] = ids
        client = TestClient(main.app)
        response = client.post("/video/upload", data=form, files={"file": ("demo.mp4", b"video", "video/mp4")})
        client.close()
        assert response.status_code == 422
        worker.assert_not_called()
        selection_database.commit.assert_not_called()
        assert list(tmp_path.iterdir()) == []
    finally:
        main.app.dependency_overrides.pop(main.get_database)


def test_selected_words_only_keep_first_occurrence(selection_database, monkeypatch, capsys):
    words = [
        {"word": "africa", "start": 1.0, "end": 1.5},
        {"word": "african", "start": 2.0, "end": 2.5},
        {"word": "african", "start": 3.0, "end": 3.5},
    ]
    assert len(match_cet4_words(words, selection_database)) == 2
    selected = match_cet4_words(words, selection_database, [2])
    assert [word["start"] for word in selected] == [2.0]
    assert all(word["meaning"] == "非洲的" for word in selected)
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _: words)
    monkeypatch.setattr(movie_task, "connect_database", lambda: selection_database)
    movie_task.process_movie(17, "demo.mp4", [2])
    cursor = selection_database.cursor.return_value.__enter__.return_value
    assert cursor.executemany.call_args.args[1] == [
        (17, "african", "非洲的", 2.0, 2.5),
    ]
    assert cursor.execute.call_args.args[1] == ("completed", 17)
    assert capsys.readouterr().out.count("] african |") == 1


def test_dedup_normalizes_case_and_keeps_earliest_time(selection_database):
    words = [
        {"word": "AFRICAN", "start": 8.0, "end": 8.5},
        {"word": " Africa ", "start": 4.0, "end": 4.5},
        {"word": " african ", "start": 2.0, "end": 2.5},
        {"word": "africa", "start": 6.0, "end": 6.5},
    ]
    expected = [
        {"word": "african", "meaning": "非洲的", "start": 2.0, "end": 2.5},
        {"word": "africa", "meaning": "非洲", "start": 4.0, "end": 4.5},
    ]
    assert match_cet4_words(words, selection_database) == expected
    # 不会跨电影/请求去重，也不会改变原始识别列表。
    assert match_cet4_words(words, selection_database) == expected
    assert len(words) == 4
