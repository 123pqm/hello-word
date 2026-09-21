"""按需截取封面；生成的缩略图保存在 uploads 下，不改动原视频。"""
from pathlib import Path
import subprocess
import tempfile

VIDEO_DIR = Path(__file__).resolve().parents[2] / "uploads" / "videos"


def get_cover(file_path: str) -> Path:
    # 统一到当前仓库的 uploads/videos，兼容整合前数据库保存的相对/绝对路径。
    filename = file_path.replace("\\", "/").rsplit("/", 1)[-1]
    root = VIDEO_DIR.resolve()
    video = (root / filename).resolve()
    if video.parent != root or not video.is_file():
        raise FileNotFoundError("视频不存在")
    stat = video.stat()
    directory = root / ".covers"
    directory.mkdir(exist_ok=True)
    cover = directory / f"{video.stem}-{stat.st_size}-{stat.st_mtime_ns}.jpg"
    if cover.is_file() and cover.stat().st_size > 0:
        return cover
    with tempfile.NamedTemporaryFile(dir=directory, suffix=".jpg", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        for seek in (1, 0):
            result = subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                 "-ss", str(seek), "-i", str(video), "-frames:v", "1",
                 "-vf", "scale=320:-2", "-q:v", "3", str(temporary)],
                check=False, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                timeout=20, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if result.returncode == 0 and temporary.is_file() and temporary.stat().st_size > 0:
                temporary.replace(cover)
                return cover
        raise ValueError("没有可用的视频画面")
    finally:
        temporary.unlink(missing_ok=True)
