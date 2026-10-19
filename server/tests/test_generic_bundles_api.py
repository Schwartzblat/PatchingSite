"""The generic ad-remover bundle: upload, newest-usable lookup, download and its paywall token."""
import hashlib
import json
import logging
import zipfile
from datetime import datetime, timezone

import jwt
import pytest
from starlette.testclient import TestClient

import config
import main
from utils import jwt_utils, patch_store
from tests.bundle_helpers import ec_keypair, make_generic_bundle
from tests.test_patch_config import reload_config  # noqa: F401  (fixture)
from tests.test_patch_read_api import bearer, make_user

URL = "/api/internal/bundles"
HDR = {"X-Internal-Token": "test-bundles", "Content-Type": "application/zip"}


@pytest.fixture
def env(tmp_db, fake_stripe, tmp_path, monkeypatch):
    key_pem, pub_pem = ec_keypair(tmp_path)
    pub_path = tmp_path / "pub.pem"
    pub_path.write_bytes(pub_pem)
    bundles = tmp_path / "bundles"
    monkeypatch.setattr(config, "BUNDLE_PUBLIC_KEY_PATH", pub_path)
    monkeypatch.setattr(config, "BUNDLES_DIR", bundles)
    monkeypatch.setattr(config, "GENERIC_PATCHING", True)
    fake_stripe.add_customer("cus_paid", "paid@example.com")
    fake_stripe.add_subscription("cus_paid", "active")
    work = tmp_path / "work"
    work.mkdir()
    with TestClient(main.app) as client:
        yield client, key_pem, work, bundles, bearer(make_user("alice", "cus_paid"))


def upload(client, key_pem, work, **kwargs):
    path, meta = make_generic_bundle(work, key_pem, **kwargs)
    data = path.read_bytes()
    r = client.post(URL, content=data, headers=HDR)
    return r, data, meta


def test_upload_generic_bundle_is_stored(env):
    client, key_pem, work, bundles, _ = env
    r, data, meta = upload(client, key_pem, work, min_store_version_code=12)
    assert r.status_code == 200, r.text
    assert r.json()["package"] == "generic"
    stored = patch_store.bundle_path("generic", "1.0.0", hashlib.sha256(data).hexdigest())
    assert stored.parent == bundles / "generic" and stored.read_bytes() == data
    row = patch_store.get_bundle("generic", "1.0.0")
    assert row["min_sdk"] == meta["min_sdk"] and row["min_store_version_code"] == 12


@pytest.mark.parametrize("kwargs", [{"with_features": True}, {"with_catalog": False}])
def test_upload_rejects_wrong_kind_generic_bundle(env, kwargs):
    client, key_pem, work, bundles, _ = env
    r, _, _ = upload(client, key_pem, work, **kwargs)
    assert r.status_code == 400, r.text
    assert patch_store.get_bundle("generic", "1.0.0") is None
    assert not bundles.exists() or not any(p.suffix == ".zip" for p in bundles.rglob("*"))


def latest(client, headers, **extra):
    return client.get("/api/patch/generic/latest", headers={**headers, **extra})


def test_generic_latest_returns_newest_usable(env):
    client, key_pem, work, _, paid = env
    upload(client, key_pem, work, patcher_version="1.0.0", name="a.zip")
    r, data, _ = upload(client, key_pem, work, patcher_version="1.1.0", name="b.zip")
    body = latest(client, paid).json()
    with zipfile.ZipFile(work / "b.zip") as zf:
        bundle_json = zf.read("bundle.json")
    assert body["package"] == "generic" and body["patcher_version"] == "1.1.0"
    assert body["bundle_id"] == hashlib.sha256(bundle_json).hexdigest()
    assert body["sha256"] == hashlib.sha256(data).hexdigest() and body["size"] == len(data)
    assert body["min_sdk"] == 33 and body["min_store_version_code"] == 0
    assert body["revision"] == patch_store.get_bundle("generic", "1.1.0")["seq"]
    upload(client, key_pem, work, patcher_version="1.0.0", name="a.zip")
    assert latest(client, paid).json()["patcher_version"] == "1.0.0"


def test_generic_latest_needs_subscription(env):
    client, _, _, _, _ = env
    assert client.get("/api/patch/generic/latest").status_code == 401
    assert latest(client, bearer(make_user("bob"))).status_code == 402


def test_generic_latest_426_for_old_store(env):
    client, key_pem, work, _, paid = env
    upload(client, key_pem, work, patcher_version="1.0.0", name="a.zip")
    upload(client, key_pem, work, patcher_version="1.1.0", min_store_version_code=500, name="b.zip")
    r = latest(client, paid, **{"X-Store-Version-Code": "400"})
    assert r.status_code == 426
    assert r.json() == {"detail": "Store update required", "min_store_version_code": 500}
    r = latest(client, paid, **{"X-Store-Version-Code": "500"})
    assert r.status_code == 200 and r.json()["patcher_version"] == "1.1.0"


def test_generic_latest_404_without_bundle(env):
    client, _, _, _, paid = env
    r = latest(client, paid)
    assert r.status_code == 404 and r.json()["detail"] == "No generic bundle yet"


def test_generic_latest_503_when_stored_file_missing(env, caplog):
    client, key_pem, work, _, paid = env
    _, data, _ = upload(client, key_pem, work)
    patch_store.bundle_path("generic", "1.0.0", hashlib.sha256(data).hexdigest()).unlink()
    patch_store._bundle_ids.clear()
    with caplog.at_level(logging.ERROR):
        r = latest(client, paid)
    assert r.status_code == 503 and r.json()["detail"] == "Generic bundle unavailable"
    assert any("Generic bundle 1.0.0 unreadable" in rec.getMessage() and rec.levelno == logging.ERROR
               for rec in caplog.records)


def test_generic_latest_503_when_stored_file_corrupt(env, caplog):
    client, key_pem, work, _, paid = env
    _, data, _ = upload(client, key_pem, work)
    patch_store.bundle_path("generic", "1.0.0", hashlib.sha256(data).hexdigest()).write_bytes(b"not a zip")
    patch_store._bundle_ids.clear()
    with caplog.at_level(logging.ERROR):
        r = latest(client, paid)
    assert r.status_code == 503 and r.json()["detail"] == "Generic bundle unavailable"
    assert any("Generic bundle 1.0.0 unreadable" in rec.getMessage() for rec in caplog.records)


def test_generic_artifacts_route_still_rejects_generic(env):
    client, _, _, _, paid = env
    r = client.get("/api/patch/generic/artifacts?patcher_version=1.0.0&apk_sha256=" + "a" * 64, headers=paid)
    assert r.status_code == 404 and r.json()["detail"] == "Unknown package"


def test_generic_bundle_download_has_etag(env):
    client, key_pem, work, _, paid = env
    _, data, _ = upload(client, key_pem, work)
    r = client.get("/api/patch/generic/bundle/1.0.0", headers=paid)
    assert r.status_code == 200 and r.content == data
    assert r.headers["ETag"] == f'"{hashlib.sha256(data).hexdigest()}"'


def app_token(client, headers, package):
    return client.post("/api/app-token", json={"package": package}, headers=headers)


def test_app_token_generic_names_generic(env):
    client, _, _, _, paid = env
    r = app_token(client, paid, "generic")
    assert r.status_code == 200, r.text
    claims = jwt.decode(r.json()["token"], config.APP_JWT_SECRET_KEY, algorithms=[jwt_utils.ALGORITHM])
    assert claims["package_name"] == "generic"
    assert r.json()["expires_at"] == datetime.fromtimestamp(claims["exp"], timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ")


def test_app_token_returns_expiry_for_every_package(env):
    client, _, _, _, paid = env
    r = app_token(client, paid, "com.whatsapp")
    assert r.status_code == 200, r.text
    expires = datetime.strptime(r.json()["expires_at"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    days = (expires - datetime.now(timezone.utc)).total_seconds() / 86400
    assert abs(days - config.APP_TOKEN_TTL_DAYS) < 0.01


def test_generic_endpoints_404_when_off(env, monkeypatch):
    client, key_pem, work, _, paid = env
    monkeypatch.setattr(config, "GENERIC_PATCHING", False)
    r, _, _ = upload(client, key_pem, work)
    assert r.status_code == 200, r.text
    for response in (latest(client, paid), client.get("/api/patch/generic/bundle/1.0.0", headers=paid),
                     app_token(client, paid, "generic")):
        assert response.status_code == 404 and response.json()["detail"] == "Generic patching is off"


@pytest.mark.parametrize("raw", ["1", "true", "YES", "On", " on "])
def test_generic_patching_env_on(monkeypatch, reload_config, raw):  # noqa: F811
    monkeypatch.setenv("GENERIC_PATCHING", raw)
    assert reload_config().GENERIC_PATCHING is True


@pytest.mark.parametrize("raw", [None, "", "  ", "0", "false", "No", "OFF"])
def test_generic_patching_env_off(monkeypatch, reload_config, raw):  # noqa: F811
    if raw is None:
        # Truly unset: also keep a .env from filling it back in on reload.
        monkeypatch.delenv("GENERIC_PATCHING", raising=False)
        monkeypatch.setattr("dotenv.load_dotenv", lambda *a, **k: False)
    else:
        monkeypatch.setenv("GENERIC_PATCHING", raw)
    assert reload_config().GENERIC_PATCHING is False


def test_generic_patching_env_rejects_garbage(monkeypatch, reload_config):  # noqa: F811
    monkeypatch.setenv("GENERIC_PATCHING", "maybe")
    with pytest.raises(RuntimeError) as exc:  # reload rebinds ConfigError, so match by name
        reload_config()
    assert type(exc.value).__name__ == "ConfigError" and "GENERIC_PATCHING" in str(exc.value)
