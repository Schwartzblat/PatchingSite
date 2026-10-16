import pytest
from fastapi.testclient import TestClient

import config
import main
from utils import patch_store

URL = "/api/internal/records"
HDR = {"X-Internal-Token": "test-crawler"}


def body(**over):
    rec = {
        "package": "com.whatsapp",
        "version_name": "2.24.1",
        "version_code": 1000,
        "apk_sha256": "a" * 64,
        "dex_sha256": "d" * 64,
        "patcher_version": "1.0",
        "artifacts": {"k": "v"},
        "missing_keys": [],
        "features_off": [],
        "supported": True,
        "source": "whatsapp.com",
    }
    rec.update(over)
    return rec


@pytest.fixture
def client(tmp_db):
    return TestClient(main.app)


def test_post_record_requires_token(client):
    assert client.post(URL, json=body()).status_code == 401
    r = client.post(URL, json=body(), headers={"X-Internal-Token": "wrong"})
    assert r.status_code == 401
    assert r.json() == {"detail": "Invalid internal token"}


def test_post_record_not_configured(client, monkeypatch):
    monkeypatch.setattr(config, "CRAWLER_SECRET", None)
    r = client.post(URL, json=body(), headers=HDR)
    assert r.status_code == 503
    assert r.json() == {"detail": "Not configured"}


def test_post_record_stores_and_returns_revision(client):
    r = client.post(URL, json=body(), headers=HDR)
    assert r.status_code == 200
    rev = r.json()["revision"]
    assert isinstance(rev, int) and rev >= 1
    stored = patch_store.find_record("com.whatsapp", "1.0", dex_sha256="d" * 64)
    assert stored is not None and stored["dex_sha256"] == "d" * 64


def test_post_record_rejects_extra_fields(client):
    assert client.post(URL, json=body(extra=1), headers=HDR).status_code == 422


def test_post_record_rejects_unknown_package(client):
    r = client.post(URL, json=body(package="com.not.known"), headers=HDR)
    assert r.status_code == 404
    assert r.json() == {"detail": "Unknown package"}


@pytest.mark.parametrize("field,value", [
    ("apk_sha256", "z" * 64), ("dex_sha256", "D" * 64), ("apk_sha256", "a" * 63),
    ("patcher_version", "../x"), ("version_code", 0), ("artifacts", {"k": 1}),
])
def test_post_record_rejects_bad_values(client, field, value):
    assert client.post(URL, json=body(**{field: value}), headers=HDR).status_code == 422


def test_post_record_rejects_bad_hash(client):
    assert client.post(URL, json=body(dex_sha256="xyz"), headers=HDR).status_code == 422


def test_reposting_identical_record_keeps_revision(client):
    first = client.post(URL, json=body(), headers=HDR).json()["revision"]
    second = client.post(URL, json=body(), headers=HDR).json()["revision"]
    assert first == second
    changed = client.post(URL, json=body(supported=False), headers=HDR).json()["revision"]
    assert changed > first


@pytest.mark.parametrize("field,value", [
    ("supported", "true"), ("supported", 1), ("version_code", "1"), ("version_code", 1.0),
])
def test_post_record_is_strict_about_types(client, field, value):
    assert client.post(URL, json=body(**{field: value}), headers=HDR).status_code == 422


def test_auth_is_checked_before_validation(client):
    assert client.post(URL, json={"bogus": 1}).status_code == 401


def test_post_record_empty_secret_is_not_configured(client, monkeypatch):
    monkeypatch.setattr(config, "CRAWLER_SECRET", "")
    assert client.post(URL, json=body(), headers=HDR).status_code == 503
