import json
import subprocess
import time
from pathlib import Path
from unittest.mock import MagicMock

import jwt
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.auth import get_current_user_id
from app.database import get_database
from app.services import movie_playback, movie_cover


def test_real_conversion_preserves_duration_and_source(tmp_path):
    source = tmp_path / 'source.mp4'
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-f', 'lavfi', '-i',
                    'color=c=blue:s=160x90:r=25', '-t', '2', '-c:v', 'libx265',
                    '-threads', '1', str(source)], check=True, capture_output=True)
    original = source.read_bytes()
    target = movie_playback.cached_path(source)
    movie_playback._convert(source, target)
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams',
                                               '-show_format', '-of', 'json', str(target)]))
    assert info['streams'][0]['codec_name'] == 'h264'
    assert info['streams'][0]['pix_fmt'] == 'yuv420p'
    assert abs(float(info['format']['duration']) - 2) < .1
    assert source.read_bytes() == original
    assert movie_playback.ensure_compatible(source) == target


def test_compatible_ticket_and_range_require_ownership(tmp_path, monkeypatch):
    key = 'test-only-secret-key-that-is-long-enough-123'
    monkeypatch.setenv('JWT_SECRET_KEY', key)
    monkeypatch.setattr(movie_cover, 'VIDEO_DIR', tmp_path)
    original = tmp_path / 'movie.mp4'
    original.write_bytes(b'original')
    compatible = movie_playback.cached_path(original)
    compatible.parent.mkdir()
    compatible.write_bytes(b'compatible-content')
    db = MagicMock()
    cursor = db.cursor.return_value.__enter__.return_value
    cursor.fetchone.return_value = {'id': 7, 'file_path': str(original)}
    app.dependency_overrides[get_database] = lambda: db
    app.dependency_overrides[get_current_user_id] = lambda: 7
    access = jwt.encode({'sub': '7', 'exp': int(time.time()) + 600}, key, algorithm='HS256')
    try:
        with TestClient(app) as client:
            response = client.post('/video/17/playback?compatible=true', headers={'Authorization': f'Bearer {access}'})
            assert response.status_code == 200
            ticket = response.json()['data']['ticket']
            claims = jwt.decode(ticket, key, algorithms=['HS256'], audience='movie-playback')
            assert claims['compatible'] is True
            streamed = client.get('/video/17/stream', params={'ticket': ticket}, headers={'Range': 'bytes=0-3'})
            assert streamed.status_code == 206 and streamed.content == b'comp'
            assert client.get('/video/18/stream', params={'ticket': ticket}).status_code == 401
            monkeypatch.setattr(movie_playback, 'ensure_compatible', lambda source: None)
            assert client.post('/video/17/playback?compatible=true', headers={'Authorization': f'Bearer {access}'}).status_code == 202
            cursor.fetchone.return_value = None
            assert client.post('/video/17/playback?compatible=true', headers={'Authorization': f'Bearer {access}'}).status_code == 404
    finally:
        app.dependency_overrides.pop(get_database, None)
        app.dependency_overrides.pop(get_current_user_id, None)
