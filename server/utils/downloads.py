"""Async APK download jobs.

Signing a build takes minutes; doing it inside the request handler pinned a
FastAPI threadpool worker for the whole time and a handful of concurrent
downloads froze the entire API. Requests now only enqueue work here.

Job states: queued -> running -> ready | failed.
"""
import hashlib
import hmac
import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

import config
from utils.apps_utils import patch_paywall, remove_file
from utils.logging_config import logger

QUEUED = 'queued'
RUNNING = 'running'
READY = 'ready'
FAILED = 'failed'
TERMINAL_STATES = (READY, FAILED)

# config.py has no knob for this yet; honour one if it grows one.
DEFAULT_MAX_QUEUE_FACTOR = 4

_jobs = {}
_lock = threading.Lock()
_cache_lock = threading.Lock()
_executor = None
_executor_lock = threading.Lock()
_stopped = False


class QueueFull(RuntimeError):
    """Raised when the pending queue is saturated - the caller answers 429."""


class TooManyJobsForUser(QueueFull):
    """This user already has DOWNLOAD_MAX_JOBS_PER_USER jobs in flight - also a 429,
    but one that tells them it is their own downloads they are waiting on."""


class WorkersStopped(RuntimeError):
    """Raised when the server is shutting down - the caller answers 503."""


@dataclass
class DownloadJob:
    job_id: str
    username: str
    package: str
    version: str
    filename: str
    state: str = QUEUED
    progress: float = 0.0
    message: str = None
    error: str = None
    path: Path = None
    size: int = None
    cached: bool = False
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    finished_at: float = None


def _max_queue() -> int:
    configured = getattr(config, 'DOWNLOAD_MAX_QUEUE', None)
    if configured:
        return int(configured)
    return max(1, int(config.DOWNLOAD_WORKERS)) * DEFAULT_MAX_QUEUE_FACTOR


def start_workers() -> ThreadPoolExecutor:
    """FastAPI lifespan startup hook."""
    global _executor, _stopped
    with _executor_lock:
        _stopped = False
        if _executor is None:
            _executor = ThreadPoolExecutor(
                max_workers=max(1, int(config.DOWNLOAD_WORKERS)),
                thread_name_prefix='download',
            )
            logger.debug('Download workers started (%s)', config.DOWNLOAD_WORKERS)
        return _executor


def stop_workers(wait: bool = True) -> None:
    """FastAPI lifespan shutdown hook. Drops queued work, waits for running jobs."""
    global _executor, _stopped
    with _executor_lock:
        executor, _executor = _executor, None
        _stopped = True
    if executor is None:
        return
    executor.shutdown(wait=wait, cancel_futures=True)
    with _lock:
        for job in _jobs.values():
            if job.state == QUEUED:
                _set(job, state=FAILED, progress=1.0, message='Server restarted, please try again',
                     error='shutdown', finished_at=time.time())
    logger.debug('Download workers stopped')


def _get_executor() -> ThreadPoolExecutor:
    """Lazy start so the module works even if the lifespan hooks are never wired."""
    with _executor_lock:
        if _stopped:
            raise WorkersStopped('download workers are shut down')
        if _executor is not None:
            return _executor
    return start_workers()


def _set(job: DownloadJob, **fields) -> None:
    for key, value in fields.items():
        setattr(job, key, value)
    job.updated_at = time.time()


def _update(job: DownloadJob, **fields) -> None:
    with _lock:
        _set(job, **fields)


def cache_dir() -> Path:
    return Path(config.DOWNLOAD_CACHE_DIR)


def source_identity(source: Path) -> str:
    """What the source build looks like on disk: name, mtime_ns and size of each file.

    Part of the cache key, because a version directory is not immutable: a
    version is re-patched in place when its patcher is fixed, and a key of
    (user, package, version) alone went on serving every user who had already
    downloaded it the old, broken build until LRU eviction. mtime_ns + size is
    what the store endpoint's version cache keys on too - cheap, and changed by
    any copy or rebuild. A directory (a bundle kept unpacked as its splits)
    contributes every file in it, so replacing one split is noticed as well.
    """
    source = Path(source)
    if source.is_dir():
        files = sorted(path for path in source.rglob('*') if path.is_file())
        names = [path.relative_to(source).as_posix() for path in files]
    else:
        files, names = [source], [source.name]
    parts = []
    for name, path in zip(names, files):
        stat = path.stat()
        parts.append(f'{name}:{stat.st_mtime_ns}:{stat.st_size}')
    return '|'.join(parts)


def cache_path(username: str, package: str, version: str, filename: str, source_id: str = '') -> Path:
    """Signed artifacts are per user (the embedded token is), so the user is part of
    the key; so is the source build's identity (see source_identity)."""
    digest = hashlib.sha256(f'{username}\0{package}\0{version}\0{source_id}'.encode()).hexdigest()
    return cache_dir() / f'{digest}{Path(filename).suffix}'


def _cache_hit(path: Path) -> bool:
    try:
        return path.is_file() and path.stat().st_size > 0
    except OSError:
        return False


def evict_cache() -> int:
    """Drop least-recently-used artifacts until the cache fits DOWNLOAD_CACHE_MAX_BYTES."""
    limit = int(config.DOWNLOAD_CACHE_MAX_BYTES)
    removed = 0
    with _cache_lock:
        directory = cache_dir()
        if not directory.is_dir():
            return 0
        entries = []
        total = 0
        for entry in directory.iterdir():
            if not entry.is_file() or entry.name.endswith('.part'):
                continue
            try:
                stat = entry.stat()
            except OSError:
                continue
            entries.append((stat.st_mtime, stat.st_size, entry))
            total += stat.st_size
        entries.sort()
        for _, size, entry in entries:
            if total <= limit:
                break
            remove_file(entry)
            total -= size
            removed += 1
    if removed:
        logger.debug('Evicted %s cached download(s)', removed)
    return removed


def reap_jobs(now: float = None) -> int:
    """Forget jobs older than DOWNLOAD_JOB_TTL_SECONDS. Cached files survive."""
    now = now if now is not None else time.time()
    ttl = int(config.DOWNLOAD_JOB_TTL_SECONDS)
    stuck_ttl = ttl + int(config.SIGNING_TIMEOUT_SECONDS)
    dropped = 0
    with _lock:
        for job_id, job in list(_jobs.items()):
            age = now - (job.finished_at or job.created_at)
            if (job.state in TERMINAL_STATES and age > ttl) or age > stuck_ttl:
                del _jobs[job_id]
                dropped += 1
    return dropped


def get_job(job_id: str, username: str):
    """Ownership is part of the lookup: another user's job simply does not exist."""
    with _lock:
        job = _jobs.get(job_id)
        if job is None or job.username != username:
            return None
        return job


def _pending_count(username: str = None) -> int:
    """Queued + running jobs, overall or for one user."""
    return sum(1 for job in _jobs.values()
               if job.state in (QUEUED, RUNNING) and (username is None or job.username == username))


def _existing_job(username: str, package: str, version: str):
    for job in _jobs.values():
        if (job.username, job.package, job.version) == (username, package, version) \
                and job.state in (QUEUED, RUNNING):
            return job
    return None


def sign_url(job_id: str, username: str, expires_at: int) -> str:
    payload = f'{job_id}:{username}:{expires_at}'.encode()
    return hmac.new(config.APP_JWT_SECRET_KEY.encode(), payload, hashlib.sha256).hexdigest()


def make_signature(job_id: str, username: str, ttl: int = None) -> str:
    ttl = int(config.DOWNLOAD_URL_TTL_SECONDS) if ttl is None else int(ttl)
    expires_at = int(time.time()) + ttl
    return f'{expires_at}.{sign_url(job_id, username, expires_at)}'


def verify_signature(job_id: str, username: str, signature: str) -> bool:
    try:
        raw_expiry, digest = str(signature).split('.', 1)
        expires_at = int(raw_expiry)
    except (AttributeError, ValueError):
        return False
    if not hmac.compare_digest(sign_url(job_id, username, expires_at), digest):
        return False
    return time.time() <= expires_at


def download_url(job: DownloadJob) -> str:
    return f'/api/downloads/{quote(job.job_id)}/file?sig={make_signature(job.job_id, job.username)}'


def accepted_state(job: DownloadJob) -> str:
    """POST /downloads only promises queued|ready, but a worker may already have
    picked the job up by the time the response is built."""
    return READY if job.state == READY else QUEUED


def job_status(job: DownloadJob) -> dict:
    ready = job.state == READY
    return {
        'job_id': job.job_id,
        'state': job.state,
        'progress': round(job.progress, 3),
        'message': job.message,
        'download_url': download_url(job) if ready else None,
        'filename': job.filename if ready else None,
        'size': job.size if ready else None,
    }


def enqueue(username: str, package: str, version: str, source_apk: Path, app_token: str,
            filename: str = None, signer=None) -> DownloadJob:
    """Return a job for (username, package, version): cached, already running, or freshly queued."""
    reap_jobs()
    source_apk = Path(source_apk)
    filename = filename or source_apk.name
    target = cache_path(username, package, version, filename, source_identity(source_apk))

    if _cache_hit(target):
        os.utime(target, None)  # LRU touch
        job = DownloadJob(
            job_id=uuid.uuid4().hex, username=username, package=package, version=version,
            filename=filename, state=READY, progress=1.0, message='Ready',
            path=target, size=target.stat().st_size, cached=True, finished_at=time.time(),
        )
        with _lock:
            _jobs[job.job_id] = job
        logger.debug('Download cache hit for %s %s %s', username, package, version)
        return job

    executor = _get_executor()
    with _lock:
        running = _existing_job(username, package, version)
        if running is not None:
            return running
        # After the join above, so asking again for a build already in flight
        # never counts against the user; cache hits returned earlier never do.
        if _pending_count(username) >= max(1, int(config.DOWNLOAD_MAX_JOBS_PER_USER)):
            raise TooManyJobsForUser(f'{username} already has downloads in flight')
        if _pending_count() >= _max_queue():
            raise QueueFull('download queue is saturated')
        job = DownloadJob(
            job_id=uuid.uuid4().hex, username=username, package=package, version=version,
            filename=filename, message='Queued',
        )
        _jobs[job.job_id] = job

    try:
        executor.submit(_run_job, job, source_apk, app_token, target, signer)
    except RuntimeError:
        _update(job, state=FAILED, progress=1.0, message='Server is restarting, please try again',
                error='executor rejected', finished_at=time.time())
        raise WorkersStopped('download workers are shut down')
    return job


def _run_job(job: DownloadJob, source_apk: Path, app_token: str, target: Path, signer) -> None:
    _update(job, state=RUNNING, progress=0.05, message='Preparing your build')
    partial = target.with_name(f'{target.name}.{job.job_id}.part')
    try:
        target.parent.mkdir(parents=True, exist_ok=True)

        def progress(fraction, message):
            _update(job, progress=fraction, message=message)

        patch_paywall(source_apk, app_token, output_path=partial, progress=progress, signer=signer)
        os.replace(partial, target)
        _update(job, state=READY, progress=1.0, message='Ready', path=target,
                size=target.stat().st_size, finished_at=time.time())
        evict_cache()
    except Exception as error:
        remove_file(partial)
        logger.exception('Download job %s failed for %s %s %s', job.job_id, job.username,
                         job.package, job.version)
        _update(job, state=FAILED, progress=1.0, message='Patching failed, please try again',
                error=f'{type(error).__name__}: {error}', finished_at=time.time())


def reset_for_tests() -> None:
    global _stopped
    stop_workers(wait=True)
    with _lock:
        _jobs.clear()
    with _executor_lock:
        _stopped = False
