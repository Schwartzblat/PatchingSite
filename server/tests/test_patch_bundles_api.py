import hashlib
import stat
import sys

import pytest
from fastapi.testclient import TestClient

import config
import main
from utils import patch_store
from tests.bundle_helpers import ec_keypair, make_bundle, resign_bundle

URL = "/api/internal/bundles"
HDR = {"X-Internal-Token": "test-bundles", "Content-Type": "application/zip"}


@pytest.fixture
def env(tmp_db, tmp_path, monkeypatch):
    key_pem, pub_pem = ec_keypair(tmp_path)
    pub_path = tmp_path / "pub.pem"
    pub_path.write_bytes(pub_pem)
    bundles = tmp_path / "bundles"
    monkeypatch.setattr(config, "BUNDLE_PUBLIC_KEY_PATH", pub_path)
    monkeypatch.setattr(config, "BUNDLES_DIR", bundles)
    work = tmp_path / "work"
    work.mkdir()
    return TestClient(main.app), key_pem, work, bundles


def test_upload_valid_bundle_is_stored(env):
    client, key_pem, work, bundles = env
    path, meta = make_bundle(work, key_pem)
    data = path.read_bytes()
    r = client.post(URL, content=data, headers=HDR)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["package"] == "com.whatsapp" and body["patcher_version"] == "3.0.0"
    stored = patch_store.bundle_path("com.whatsapp", "3.0.0", hashlib.sha256(data).hexdigest())
    assert stored.name.startswith("3.0.0-") and stored.parent == bundles / "com.whatsapp"
    assert stat.S_IMODE(stored.stat().st_mode) == 0o644
    assert stored.read_bytes() == data
    row = patch_store.get_bundle("com.whatsapp", "3.0.0")
    assert row["size"] == len(data) and row["min_store_version_code"] == 7
    assert row["seq"] == body["revision"]
    assert [p.name for p in bundles.iterdir()] == ["com.whatsapp"]


def test_upload_rejects_bad_signature(env, tmp_path):
    client, _, work, bundles = env
    other_key, _ = ec_keypair(tmp_path, "other.pem")
    path, _ = make_bundle(work, other_key)
    r = client.post(URL, content=path.read_bytes(), headers=HDR)
    assert r.status_code == 400
    assert not [p for p in bundles.rglob("*") if p.is_file()]
    assert patch_store.get_bundle("com.whatsapp", "3.0.0") is None


def test_upload_rejects_garbage(env):
    client, _, _, bundles = env
    r = client.post(URL, content=b"not a zip", headers=HDR)
    assert r.status_code == 400
    assert not [p for p in bundles.rglob("*") if p.is_file()]


def test_upload_rejects_unknown_package(env):
    client, key_pem, work, bundles = env
    path, _ = make_bundle(work, key_pem, package="com.nope.app")
    r = client.post(URL, content=path.read_bytes(), headers=HDR)
    assert r.status_code == 404
    assert not [p for p in bundles.rglob("*") if p.is_file()]


def test_upload_rejects_bad_patcher_version(env):
    client, key_pem, work, bundles = env
    path, _ = make_bundle(work, key_pem, patcher_version="../x")
    r = client.post(URL, content=path.read_bytes(), headers=HDR)
    assert r.status_code == 422
    assert not [p for p in bundles.rglob("*") if p.is_file()]


def test_upload_rejects_oversized_body(env):
    client, _, _, bundles = env
    r = client.post(URL, content=b"\0" * (16 * 1024 * 1024 + 1), headers=HDR)
    assert r.status_code == 413
    assert r.json() == {"detail": "Bundle too large"}
    assert not [p for p in bundles.rglob("*") if p.is_file()]


def test_upload_requires_token(env):
    client, key_pem, work, _ = env
    path, _ = make_bundle(work, key_pem)
    data = path.read_bytes()
    assert client.post(URL, content=data).status_code == 401
    r = client.post(URL, content=data, headers={"X-Internal-Token": "wrong"})
    assert r.status_code == 401


def test_upload_without_public_key_is_503(env, tmp_path, monkeypatch):
    client, key_pem, work, _ = env
    monkeypatch.setattr(config, "BUNDLE_PUBLIC_KEY_PATH", tmp_path / "missing.pem")
    path, _ = make_bundle(work, key_pem)
    assert client.post(URL, content=path.read_bytes(), headers=HDR).status_code == 503


def test_reupload_bumps_revision(env):
    client, key_pem, work, _ = env
    path, _ = make_bundle(work, key_pem)
    data = path.read_bytes()
    first = client.post(URL, content=data, headers=HDR).json()["revision"]
    second = client.post(URL, content=data, headers=HDR).json()["revision"]
    assert second > first


def test_reupload_different_bytes_switches_file(env):
    client, key_pem, work, bundles = env
    first, _ = make_bundle(work, key_pem, min_store_version_code=7)
    assert client.post(URL, content=first.read_bytes(), headers=HDR).status_code == 200
    second, _ = make_bundle(work, key_pem, min_store_version_code=9, name="b2.zip")
    data2 = second.read_bytes()
    assert data2 != first.read_bytes()
    assert client.post(URL, content=data2, headers=HDR).status_code == 200
    row = patch_store.get_bundle("com.whatsapp", "3.0.0")
    sha = hashlib.sha256(data2).hexdigest()
    assert row["sha256"] == sha and row["min_store_version_code"] == 9
    files = [p for p in bundles.rglob("*") if p.is_file()]
    assert files == [patch_store.bundle_path("com.whatsapp", "3.0.0", sha)]


def test_upload_rejects_missing_meta_keys(env):
    client, key_pem, work, bundles = env
    path, _ = make_bundle(work, key_pem)
    bad = resign_bundle(path, work / "bad.zip", key_pem, lambda m: m.pop("min_sdk"))
    r = client.post(URL, content=bad.read_bytes(), headers=HDR)
    assert r.status_code == 400 and "min_sdk" in r.json()["detail"]
    assert not [p for p in bundles.rglob("*") if p.is_file()]
    assert patch_store.get_bundle("com.whatsapp", "3.0.0") is None


def test_upload_rejects_oversized_chunked_body(env):
    client, _, _, bundles = env

    def chunks():
        for _ in range(17):
            yield b"\0" * (1024 * 1024)

    r = client.post(URL, content=chunks(), headers=HDR)
    assert r.status_code == 413
    assert not [p for p in bundles.rglob("*") if p.is_file()]


def test_site_starts_and_upload_is_503_without_stitch_bundle(env, monkeypatch):
    import importlib

    client, key_pem, work, bundles = env
    monkeypatch.setitem(sys.modules, "stitch.bundle", None)
    importlib.reload(importlib.import_module("api.patching"))  # module import must not need stitch.bundle
    r = client.post("/api/internal/bundles", content=b"x",
                    headers={"X-Internal-Token": config.BUNDLE_UPLOAD_SECRET})
    assert r.status_code == 503 and r.json()["detail"] == "Not configured"
