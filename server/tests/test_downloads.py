"""Async download flow: selection, path safety, job lifecycle, signed URLs."""
import io
import os
import tempfile
import time
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import config
import database  # noqa: F401  (imported first: jwt_utils <-> database is circular)
from api import apps
from utils import apps_utils, downloads
from utils.jwt_utils import TokenData, get_token_data
from utils.stripe_utils import SubscriptionInfo, get_subscription_info

PACKAGE = "com.whatsapp"
VERSION = "2.26.26.73"


def make_apk(path: Path, entries=None) -> Path:
    """A minimal but real zip, so zipfile/is_bundle behave like they do in production."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zip_file:
        zip_file.writestr("classes.dex", "dex" * 100)
        for name, payload in (entries or {}).items():
            zip_file.writestr(name, payload)
    return path


def make_version_dir(root: Path, package=PACKAGE, version=VERSION, patched=True, original=True) -> Path:
    directory = root / package / version
    directory.mkdir(parents=True, exist_ok=True)
    if original:
        make_apk(directory / f"{package}-{version}.xapk", {"main.apk": "x" * 4000})
    if patched:
        make_apk(directory / f"patched_{package}-{version}.xapk", {"main.apk": "y" * 2000})
    return directory


def fake_signer(bundle_dir, apk_path, output_apk, is_bundle_file):
    """Stands in for fastsigner - tests never shell out to the real binary."""
    if is_bundle_file:
        for apk in Path(bundle_dir).glob("*.apk"):
            apk.write_bytes(apk.read_bytes() + b"\x00signed")
        return
    os.replace(apk_path, output_apk)


def failing_signer(bundle_dir, apk_path, output_apk, is_bundle_file):
    raise RuntimeError("signing blew up")


@pytest.fixture
def cache_dir(monkeypatch, tmp_path):
    directory = tmp_path / "download_cache"
    directory.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(config, "DOWNLOAD_CACHE_DIR", directory)
    return directory


@pytest.fixture(autouse=True)
def clean_registry():
    downloads.reset_for_tests()
    yield
    downloads.reset_for_tests()


@pytest.fixture
def client(patched_apps_dir, cache_dir):
    app = FastAPI()
    app.include_router(apps.router, prefix="/api")
    app.dependency_overrides[get_token_data] = lambda: TokenData(
        username="alice", email="alice@example.com", customer_id="cus_alice",
        exp=datetime.now(timezone.utc) + timedelta(hours=1),
    )
    app.dependency_overrides[get_subscription_info] = lambda: SubscriptionInfo(
        client_id="cus_alice", email="alice@example.com", status="active"
    )
    with TestClient(app) as test_client:
        test_client.patched_apps_dir = patched_apps_dir
        yield test_client


def temp_files():
    return set(Path(tempfile.gettempdir()).glob("patch-*"))


# --- file selection -------------------------------------------------------

def test_patched_file_is_chosen_over_the_original(patched_apps_dir):
    directory = make_version_dir(patched_apps_dir)
    chosen = apps_utils.find_patched_apk(directory)
    assert chosen.name == f"patched_{PACKAGE}-{VERSION}.xapk"


def test_find_patched_apk_returns_none_for_empty_dir(patched_apps_dir, tmp_path):
    directory = patched_apps_dir / PACKAGE / VERSION
    directory.mkdir(parents=True)
    assert apps_utils.find_patched_apk(directory) is None


def test_list_versions_uses_the_patched_file(client):
    make_version_dir(client.patched_apps_dir)
    (client.patched_apps_dir / PACKAGE / "1.0.0").mkdir(parents=True)  # empty dir is skipped

    response = client.get(f"/api/list/{PACKAGE}")
    assert response.status_code == 200
    body = response.json()
    assert [item["version"] for item in body] == [VERSION]
    item = body[0]
    assert item["filename"].startswith("patched")
    assert item["id"] == f"{PACKAGE}:{VERSION}"
    assert isinstance(item["size"], int)
    assert "href" not in item


@pytest.mark.parametrize("older, newer", [
    ("5.9", "5.10"),
    ("99.0", "100.0"),
    ("2.26.9.1", "2.26.10.0"),
    ("5.199.1.1804", "5.200.1.1806"),
    ("1.0", "1.0.1"),
    ("1.0-beta", "1.0"),        # a pre-release comes before its release
    ("1.0-beta", "1.0-rc"),
    ("1.9z", "1.10a"),
])
def test_versions_order_by_their_numbers_not_their_text(older, newer):
    from utils.utils import version_sort_key

    assert version_sort_key(older) < version_sort_key(newer)
    assert sorted([newer, older], key=version_sort_key) == [older, newer]


def test_list_versions_is_newest_first_numerically(client):
    for version in ("5.9", "5.10", "99.0", "100.0"):
        make_version_dir(client.patched_apps_dir, version=version)

    body = client.get(f"/api/list/{PACKAGE}").json()

    assert [item["version"] for item in body] == ["100.0", "99.0", "5.10", "5.9"]


def test_list_versions_unknown_package_is_404(client):
    response = client.get("/api/list/com.evil.app")
    assert response.status_code == 404


def test_list_versions_missing_package_dir_is_404(client):
    response = client.get(f"/api/list/{PACKAGE}")
    assert response.status_code == 404


# --- error mapping --------------------------------------------------------

def test_download_unknown_package_is_404(client):
    response = client.post("/api/downloads", json={"package": "com.evil.app", "version": VERSION})
    assert response.status_code == 404


def test_download_empty_version_dir_is_404(client):
    (client.patched_apps_dir / PACKAGE / VERSION).mkdir(parents=True)
    response = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION})
    assert response.status_code == 404


def test_download_only_original_present_is_404(client):
    make_version_dir(client.patched_apps_dir, patched=False)
    response = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION})
    assert response.status_code == 404


@pytest.mark.parametrize("version", ["../../../etc", "..", "../2.26.26.73", "a/../../b", ""])
def test_path_traversal_in_version_is_rejected(client, version):
    make_version_dir(client.patched_apps_dir)
    response = client.post("/api/downloads", json={"package": PACKAGE, "version": version})
    assert response.status_code in (404, 422)


def test_path_traversal_via_symlink_is_rejected(patched_apps_dir, tmp_path):
    from utils import utils

    outside = tmp_path / "outside"
    (outside / "leak").mkdir(parents=True)
    (patched_apps_dir / PACKAGE).mkdir(parents=True)
    (patched_apps_dir / PACKAGE / "leak").symlink_to(outside / "leak", target_is_directory=True)

    with pytest.raises(Exception) as excinfo:
        utils.version_dir(PACKAGE, "leak")
    assert getattr(excinfo.value, "status_code", None) == 404


def test_package_traversal_is_rejected():
    from utils import utils

    for package in ("../etc", "com.whatsapp/../../etc", "", "."):
        with pytest.raises(Exception) as excinfo:
            utils.package_dir(package)
        assert getattr(excinfo.value, "status_code", None) == 404


# --- patch_paywall --------------------------------------------------------

def test_plain_apk_no_longer_raises_name_error(tmp_path):
    """The non-bundle branch used to reference an unbound main_apk_name."""
    source = make_apk(tmp_path / "plain.apk")
    before = temp_files()

    output = apps_utils.patch_paywall(source, "jwt-token", signer=fake_signer)

    try:
        assert output.is_file()
        with zipfile.ZipFile(output) as zip_file:
            assert "assets/paywall.json" in zip_file.namelist()
            assert b"jwt-token" in zip_file.read("assets/paywall.json")
        assert temp_files() - before == {output}
    finally:
        apps_utils.remove_file(output)


def test_bundle_is_repacked_and_temp_files_cleaned(tmp_path):
    source = make_apk(tmp_path / "bundle.xapk", {"main.apk": _inner_apk_bytes()})
    before = temp_files()

    output = apps_utils.patch_paywall(source, "jwt-token", signer=fake_signer)

    try:
        assert output.is_file()
        with zipfile.ZipFile(output) as bundle:
            assert "main.apk" in bundle.namelist()
            with zipfile.ZipFile(io.BytesIO(bundle.read("main.apk"))) as inner:
                assert "assets/paywall.json" in inner.namelist()
        assert temp_files() - before == {output}
    finally:
        apps_utils.remove_file(output)


def test_a_plain_apk_carrying_a_nested_apk_is_not_a_bundle(tmp_path):
    """Stitch embeds its helpers in patched builds as assets/stitch/*.apk.

    Read as a bundle, the token went into the largest helper and the build itself
    was re-zipped with no signature at all, which the store app refuses to install.
    """
    helper = _inner_apk_bytes()
    source = make_apk(tmp_path / "plain.apk", {
        "AndroidManifest.xml": "manifest",
        "assets/stitch/com.paywall.apk": helper,
    })
    calls = []

    def recording_signer(*args):
        calls.append(args)
        fake_signer(*args)

    output = apps_utils.patch_paywall(source, "jwt-token", signer=recording_signer)

    try:
        (_, _, _, is_bundle_file), = calls
        assert is_bundle_file is False
        with zipfile.ZipFile(output) as apk:
            assert b"jwt-token" in apk.read("assets/paywall.json")
            assert apk.read("assets/stitch/com.paywall.apk") == helper
    finally:
        apps_utils.remove_file(output)


def test_failed_signing_leaves_no_temp_files(tmp_path):
    source = make_apk(tmp_path / "plain.apk")
    output_path = tmp_path / "out.apk"
    before = temp_files()

    with pytest.raises(RuntimeError):
        apps_utils.patch_paywall(source, "jwt-token", output_path=output_path, signer=failing_signer)

    assert temp_files() == before
    assert not output_path.exists()


def _inner_apk_bytes() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as zip_file:
        zip_file.writestr("classes.dex", "dex" * 500)
    return buffer.getvalue()


# --- job registry ---------------------------------------------------------

def enqueue(username="alice", signer=fake_signer, source=None, package=PACKAGE, version=VERSION):
    return downloads.enqueue(
        username=username, package=package, version=version,
        source_apk=source, app_token="jwt-token", signer=signer,
    )


def wait_for(job, states=downloads.TERMINAL_STATES, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if job.state in states:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job stuck in {job.state}")


def test_job_runs_and_caches(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")

    job = enqueue(source=source)
    assert job.state in (downloads.QUEUED, downloads.RUNNING, downloads.READY)
    wait_for(job)
    assert job.state == downloads.READY, job.error
    assert job.progress == 1.0
    assert job.path.parent == cache_dir
    assert job.size > 0

    again = enqueue(source=source)
    assert again.state == downloads.READY
    assert again.cached is True
    assert again.job_id != job.job_id


def test_a_build_rebuilt_in_place_is_not_served_from_cache(cache_dir, tmp_path):
    """Re-patching a version overwrites its file; the old signed copy must not outlive it."""
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    first = wait_for(enqueue(source=source))
    assert first.state == downloads.READY

    source.unlink()
    make_apk(source, {"assets/fix.txt": "rebuilt"})
    stat = source.stat()
    os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))

    again = enqueue(source=source)
    assert again.cached is False
    wait_for(again)
    assert again.state == downloads.READY, again.error
    assert again.path != first.path
    with zipfile.ZipFile(again.path) as archive:
        assert "assets/fix.txt" in archive.namelist()


def test_an_unchanged_build_is_still_a_cache_hit(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    wait_for(enqueue(source=source))
    assert enqueue(source=source).cached is True


def test_source_identity_covers_every_file_of_an_unpacked_bundle(tmp_path):
    bundle = tmp_path / "bundle"
    bundle.mkdir()
    (bundle / "base.apk").write_bytes(b"a" * 10)
    (bundle / "split_config.arm64_v8a.apk").write_bytes(b"b" * 10)
    before = downloads.source_identity(bundle)

    (bundle / "split_config.arm64_v8a.apk").write_bytes(b"b" * 11)

    assert downloads.source_identity(bundle) != before


def test_source_identity_changes_with_size_or_mtime(tmp_path):
    source = make_apk(tmp_path / "patched.xapk")
    before = downloads.source_identity(source)
    stat = source.stat()
    os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1))
    assert downloads.source_identity(source) != before


def test_cache_is_per_user(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    wait_for(enqueue(username="alice", source=source))
    bob = enqueue(username="bob", source=source)
    assert bob.cached is False


def test_failed_job_reports_failed_state(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    job = wait_for(enqueue(source=source, signer=failing_signer))
    assert job.state == downloads.FAILED
    assert "signing blew up" in job.error
    assert list(cache_dir.glob("*")) == []


def test_queue_is_bounded(monkeypatch, cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    release = _blocking_signer()
    monkeypatch.setattr(config, "DOWNLOAD_WORKERS", 1)
    monkeypatch.setattr(config, "DOWNLOAD_MAX_QUEUE", 2, raising=False)
    downloads.reset_for_tests()

    jobs = [enqueue(username=f"user{index}", source=source, signer=release.signer) for index in range(2)]
    try:
        with pytest.raises(downloads.QueueFull):
            enqueue(username="user-overflow", source=source, signer=release.signer)
    finally:
        release.event.set()
    for job in jobs:
        wait_for(job)


def test_one_user_cannot_fill_the_queue(monkeypatch, cache_dir, tmp_path):
    """The global queue is 8 slots; without a per-user cap one subscriber
    clicking through every version locked everyone else out."""
    monkeypatch.setattr(config, "DOWNLOAD_MAX_JOBS_PER_USER", 2)
    release = _blocking_signer()
    sources = [make_apk(tmp_path / f"v{index}" / f"patched_{PACKAGE}.apk") for index in range(4)]

    def mine(index, username="alice"):
        return enqueue(username=username, source=sources[index], version=f"1.{index}", signer=release.signer)

    jobs = [mine(0), mine(1)]
    try:
        with pytest.raises(downloads.TooManyJobsForUser):
            mine(2)
        # Asking again for a build already in flight joins it rather than counting twice.
        assert mine(0).job_id == jobs[0].job_id
        # Someone else is unaffected.
        jobs.append(mine(3, username="bob"))
    finally:
        release.event.set()
    for job in jobs:
        wait_for(job)

    # Finished jobs free the slots.
    assert wait_for(mine(2)).state == downloads.READY


def test_the_per_user_cap_is_a_clear_429(client, monkeypatch):
    def _full(**kwargs):
        raise downloads.TooManyJobsForUser("alice already has 2 jobs")

    monkeypatch.setattr(downloads, "enqueue", _full)
    make_version_dir(client.patched_apps_dir)

    response = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION})

    assert response.status_code == 429
    assert response.json()["detail"] == (
        f"You already have {config.DOWNLOAD_MAX_JOBS_PER_USER} downloads being prepared. "
        "Wait for one to finish, then try again.")


class _blocking_signer:
    def __init__(self):
        import threading

        self.event = threading.Event()

    def signer(self, bundle_dir, apk_path, output_apk, is_bundle_file):
        self.event.wait(20)
        fake_signer(bundle_dir, apk_path, output_apk, is_bundle_file)


def test_reap_drops_old_jobs(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    job = wait_for(enqueue(source=source))
    assert downloads.get_job(job.job_id, "alice") is not None
    downloads.reap_jobs(now=time.time() + config.DOWNLOAD_JOB_TTL_SECONDS + 1)
    assert downloads.get_job(job.job_id, "alice") is None


def test_cache_eviction_is_lru(monkeypatch, cache_dir):
    monkeypatch.setattr(config, "DOWNLOAD_CACHE_MAX_BYTES", 100)
    old = cache_dir / "old.apk"
    new = cache_dir / "new.apk"
    old.write_bytes(b"a" * 80)
    new.write_bytes(b"b" * 80)
    os.utime(old, (1, 1))

    downloads.evict_cache()

    assert not old.exists()
    assert new.exists()


# --- ownership and signed urls -------------------------------------------

def test_other_user_cannot_read_a_job(cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    job = wait_for(enqueue(username="alice", source=source))
    assert downloads.get_job(job.job_id, "alice") is job
    assert downloads.get_job(job.job_id, "mallory") is None


def test_signature_round_trip():
    signature = downloads.make_signature("job-1", "alice")
    assert downloads.verify_signature("job-1", "alice", signature)
    assert not downloads.verify_signature("job-2", "alice", signature)
    assert not downloads.verify_signature("job-1", "mallory", signature)


def test_expired_signature_is_rejected():
    signature = downloads.make_signature("job-1", "alice", ttl=-1)
    assert not downloads.verify_signature("job-1", "alice", signature)


@pytest.mark.parametrize("signature", [
    "", "nope", "1.2.3", f"{int(time.time()) + 600}.{'0' * 64}", "abc.def",
])
def test_tampered_signature_is_rejected(signature):
    assert not downloads.verify_signature("job-1", "alice", signature)


def test_expiry_cannot_be_extended():
    expires_at = int(time.time()) + 60
    digest = downloads.sign_url("job-1", "alice", expires_at)
    assert not downloads.verify_signature("job-1", "alice", f"{expires_at + 10_000}.{digest}")


# --- end to end through the router ---------------------------------------

def test_enqueue_poll_and_download(client, monkeypatch, cache_dir):
    monkeypatch.setattr(apps_utils, "sign_apk", fake_signer)
    make_version_dir(client.patched_apps_dir)

    created = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION})
    assert created.status_code == 202
    body = created.json()
    assert body["state"] in (downloads.QUEUED, downloads.READY)
    job_id = body["job_id"]

    deadline = time.time() + 30
    status = None
    while time.time() < deadline:
        status = client.get(f"/api/downloads/{job_id}").json()
        if status["state"] in downloads.TERMINAL_STATES:
            break
        time.sleep(0.05)

    assert status["state"] == downloads.READY, status
    assert status["progress"] == 1.0
    assert status["filename"].startswith("patched")
    assert status["size"] > 0

    file_response = client.get(status["download_url"])
    assert file_response.status_code == 200
    assert "attachment" in file_response.headers["content-disposition"]
    assert len(file_response.content) == status["size"]


def test_a_prepared_download_resumes_with_a_range_request(client, monkeypatch, cache_dir):
    """The Android client resumes a 40-85 MB transfer with Range instead of restarting it."""
    monkeypatch.setattr(apps_utils, "sign_apk", fake_signer)
    make_version_dir(client.patched_apps_dir)
    job_id = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION}).json()["job_id"]
    wait_for(downloads.get_job(job_id, "alice"))
    url = client.get(f"/api/downloads/{job_id}").json()["download_url"]

    whole = client.get(url)
    assert whole.headers.get("accept-ranges") == "bytes"
    size = len(whole.content)

    tail = client.get(url, headers={"Range": "bytes=10-"})

    assert tail.status_code == 206
    assert tail.headers["content-range"] == f"bytes 10-{size - 1}/{size}"
    assert tail.content == whole.content[10:]


def test_file_endpoint_rejects_bad_signature(client, monkeypatch, cache_dir):
    monkeypatch.setattr(apps_utils, "sign_apk", fake_signer)
    make_version_dir(client.patched_apps_dir)
    job_id = client.post("/api/downloads", json={"package": PACKAGE, "version": VERSION}).json()["job_id"]
    wait_for(downloads.get_job(job_id, "alice"))

    assert client.get(f"/api/downloads/{job_id}/file").status_code == 422
    assert client.get(f"/api/downloads/{job_id}/file?sig=nope").status_code == 404
    expired = downloads.make_signature(job_id, "alice", ttl=-1)
    assert client.get(f"/api/downloads/{job_id}/file?sig={expired}").status_code == 404


def test_job_of_another_user_is_404(client, cache_dir, tmp_path):
    source = make_apk(tmp_path / f"patched_{PACKAGE}.apk")
    job = wait_for(enqueue(username="mallory", source=source))
    signature = downloads.make_signature(job.job_id, "mallory")

    assert client.get(f"/api/downloads/{job.job_id}").status_code == 404
    assert client.get(f"/api/downloads/{job.job_id}/file?sig={signature}").status_code == 404


def test_removed_routes_are_gone(client):
    assert client.get(f"/api/patch/{PACKAGE}/{VERSION}").status_code == 404
    assert client.get(f"/api/path/{PACKAGE}/latest").status_code == 404
    assert client.get(f"/api/download/{PACKAGE}/{VERSION}").status_code == 404


# --- catalogue endpoint ---

def test_apps_endpoint_lists_the_catalogue(client):
    response = client.get("/api/apps")
    assert response.status_code == 200

    body = response.json()
    assert {app["package"] for app in body} == {
        "com.whatsapp", "com.tranzmate", "com.keshet.mako.VOD", "com.applicaster.iReshet",
        "com.okcupid.okcupid",
    }

    whatsapp = next(app for app in body if app["package"] == "com.whatsapp")
    assert whatsapp["key"] == "whatsapp"
    assert whatsapp["name"] == "WhatsApp"
    # Compared against the config rather than a literal: this copy tracks the
    # patcher's README and moves with it, while what the test guards is that the
    # endpoint serializes the tuple at all. The second assert keeps the first
    # from passing vacuously if the tuple is ever emptied.
    assert whatsapp["features"] == list(config.APPS_BY_PACKAGE["com.whatsapp"].features)
    assert whatsapp["features"]


def test_apps_endpoint_needs_no_authentication():
    """The catalogue is public, exactly as it is on the website."""
    app = FastAPI()
    app.include_router(apps.router, prefix="/api")
    with TestClient(app) as unauthenticated:
        assert unauthenticated.get("/api/apps").status_code == 200
