import shutil
import subprocess

import pytest

from app.services import movie_cover


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="需要本机 FFmpeg")
def test_short_video_cover_and_cache(tmp_path, monkeypatch):
    video = tmp_path / "sample.mp4"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=blue:s=320x180:d=0.2",
                    "-c:v", "mpeg4", str(video)], check=True)
    monkeypatch.setattr(movie_cover, "VIDEO_DIR", tmp_path)
    cover = movie_cover.get_cover(r"uploads\videos\sample.mp4")
    assert cover.read_bytes().startswith(b"\xff\xd8")
    before = cover.stat().st_mtime_ns
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("缓存命中不应重新截帧"))
    assert movie_cover.get_cover(str(video)) == cover
    assert cover.stat().st_mtime_ns == before


def test_missing_video_does_not_generate_cover(tmp_path, monkeypatch):
    monkeypatch.setattr(movie_cover, "VIDEO_DIR", tmp_path)
    with pytest.raises(FileNotFoundError):
        movie_cover.get_cover("missing.mp4")
