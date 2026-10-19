"""On-demand H.264/AAC playback copies. Originals and subtitle timestamps stay intact."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Lock
import subprocess
import tempfile

_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="compatible-video")
_lock = Lock()
_jobs = {}


def cached_path(source: Path) -> Path:
    stat = source.stat()
    return source.parent / '.playback' / f'{source.stem}-{stat.st_size}-{stat.st_mtime_ns}.mp4'


def _convert(source: Path, target: Path) -> None:
    target.parent.mkdir(exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, suffix='.mp4', delete=False) as stream:
        temporary = Path(stream.name)
    try:
        subprocess.run([
            'ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-i', str(source),
            '-map', '0:v:0', '-map', '0:a:0?', '-vf', "scale='min(1280,iw)':-2",
            '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-pix_fmt', 'yuv420p',
            '-threads', '2', '-c:a', 'aac', '-ac', '2', '-b:a', '128k',
            '-movflags', '+faststart', str(temporary)
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=7200,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if temporary.stat().st_size == 0:
            raise RuntimeError('empty playback copy')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def ensure_compatible(source: Path) -> Path | None:
    target = cached_path(source)
    if target.is_file() and target.stat().st_size > 0:
        return target
    with _lock:
        job = _jobs.get(target)
        if job is not None:
            if job.done():
                # Do not endlessly re-encode a broken source on each polling request.
                job.result()
                return target
            return None
        if sum(not task.done() for task in _jobs.values()) >= 4:
            raise RuntimeError('playback conversion queue full')
        _jobs[target] = _pool.submit(_convert, source, target)
    return None
