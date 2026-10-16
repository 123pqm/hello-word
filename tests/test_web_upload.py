"""网页 multipart 上传合约测试，不连接真实数据库或运行 Whisper。"""
from unittest.mock import MagicMock

from fastapi.testclient import TestClient
import pytest

import app.main as main
from app.auth import get_current_user_id


@pytest.fixture
def web_upload(monkeypatch, tmp_path):
    database = MagicMock()
    database.cursor.return_value.__enter__.return_value.lastrowid = 23
    worker = MagicMock()
    monkeypatch.setenv('JWT_SECRET_KEY', 'web-upload-test-secret-' * 3)
    monkeypatch.setattr(main, 'UPLOAD_DIR', tmp_path)
    monkeypatch.setattr(main, 'process_movie', worker)
    previous = main.app.dependency_overrides.copy()
    main.app.dependency_overrides[main.get_database] = lambda: database
    client = TestClient(main.app)  # No lifespan; do not initialize the real DB.
    try:
        yield client, database, worker, tmp_path
    finally:
        client.close()
        main.app.dependency_overrides.clear()
        main.app.dependency_overrides.update(previous)


def test_web_upload_requires_login(web_upload):
    client, database, worker, directory = web_upload
    response = client.post('/video/web/upload', files={'file': ('demo.mp4', b'video', 'video/mp4')})
    assert response.status_code == 401
    database.commit.assert_not_called()
    worker.assert_not_called()
    assert not list(directory.iterdir())


def test_web_upload_saves_bytes_and_owner_and_schedules_once(web_upload):
    client, database, worker, directory = web_upload
    main.app.dependency_overrides[get_current_user_id] = lambda: 42
    response = client.post('/video/web/upload', files={'file': ('lesson.mp4', b'uploaded-video', 'video/mp4')})
    assert response.status_code == 200, response.text
    data = response.json()['data']
    assert data['movie_id'] == 23 and data['status'] == 'pending'
    assert data['originalFilename'] == 'lesson.mp4'
    assert data['tracking_token']
    files = list(directory.iterdir())
    assert len(files) == 1 and files[0].read_bytes() == b'uploaded-video'
    cursor = database.cursor.return_value.__enter__.return_value
    assert cursor.execute.call_args.args[1][0] == 42
    database.commit.assert_called_once()
    worker.assert_called_once_with(23, str(files[0].resolve()), None, 1)


@pytest.mark.parametrize('files,data,expected', [
    (None, {'file': 'C:/movies/lesson.mp4'}, 422),
    ({'file': ('notes.txt', b'not video', 'text/plain')}, {}, 400),
    ({'file': ('clip.exe', b'not video', 'video/mp4')}, {}, 400),
])
def test_web_upload_rejects_paths_and_invalid_files(web_upload, files, data, expected):
    client, database, worker, directory = web_upload
    main.app.dependency_overrides[get_current_user_id] = lambda: 42
    response = client.post('/video/web/upload', files=files, data=data)
    assert response.status_code == expected
    database.commit.assert_not_called()
    worker.assert_not_called()
    assert not list(directory.iterdir())


def test_web_upload_rolls_back_and_removes_file_on_db_failure(web_upload):
    client, database, worker, directory = web_upload
    main.app.dependency_overrides[get_current_user_id] = lambda: 42
    database.commit.side_effect = RuntimeError('test failure')
    response = client.post('/video/web/upload', files={'file': ('lesson.mp4', b'video', 'video/mp4')})
    assert response.status_code == 500
    database.rollback.assert_called_once()
    worker.assert_not_called()
    assert not list(directory.iterdir())
