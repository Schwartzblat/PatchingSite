"""Store read endpoints: latest supported build, build lookup by hash, bundle download."""
from datetime import datetime, timezone

import pytest
from starlette.testclient import TestClient

import config
import database
from utils import jwt_utils, patch_store, ratelimit
from tests.test_patch_records_api import body

PW = "correct horse battery"


def make_user(username, customer_id=None):
    assert database.register_user(database.RegisterRequest(
        username=username, email=f"{username}@example.com", password=PW))
    user = database.get_user(username)
    assert database.mark_email_verified(username, user.email, datetime.now(timezone.utc))
    if customer_id:
        assert database.update_user_customer_id("username", username, customer_id)
    return database.get_user(username)


def bearer(user):
    token = jwt_utils.create_access_token(user, client=jwt_utils.TOKEN_CLIENT_MOBILE)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client(tmp_db, fake_stripe, tmp_path, monkeypatch):
    import main

    monkeypatch.setattr(config, "BUNDLES_DIR", tmp_path / "bundles")
    fake_stripe.add_customer("cus_paid", "paid@example.com")
    fake_stripe.add_subscription("cus_paid", "active")
    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def paid(client):
    return bearer(make_user("alice", "cus_paid"))


def add_bundle(package="com.whatsapp", patcher_version="1.0", min_store=0, data=b"zipbytes",
               sha="b" * 64):
    patch_store.upsert_bundle({"package": package, "patcher_version": patcher_version,
                               "min_store_version_code": min_store, "min_sdk": 33}, sha, len(data))
    path = patch_store.bundle_path(package, patcher_version, sha)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def latest(client, headers, package="com.whatsapp", **extra):
    return client.get(f"/api/patch/{package}/latest", headers={**headers, **extra})


def test_latest_needs_subscription(client, tmp_db):
    free = bearer(make_user("bob"))
    assert latest(client, free).status_code == 402
    assert client.get("/api/patch/com.whatsapp/latest").status_code == 401


def test_latest_returns_newest_supported(client, paid):
    add_bundle()
    patch_store.upsert_record(body(version_code=1000, version_name="2.24.1"))
    patch_store.upsert_record(body(version_code=1100, version_name="2.24.2", dex_sha256="e" * 64,
                                   apk_sha256="f" * 64, features_off=["x"]))
    patch_store.upsert_record(body(version_code=1200, dex_sha256="1" * 64, supported=False))
    r = latest(client, paid)
    assert r.status_code == 200
    data = r.json()
    assert data == {
        "package": "com.whatsapp", "version_code": 1100, "version_name": "2.24.2",
        "apk_url": config.OFFICIAL_APK_URLS["com.whatsapp"], "apk_sha256": "f" * 64,
        "dex_sha256": "e" * 64, "patcher_version": "1.0", "revision": data["revision"],
        "features_off": ["x"]}
    assert isinstance(data["revision"], int)


def test_latest_requires_matching_bundle(client, paid):
    patch_store.upsert_record(body())
    r = latest(client, paid)
    assert r.status_code == 404 and r.json()["detail"] == "No supported version yet"


def test_latest_respects_min_store_version(client, paid):
    add_bundle(min_store=50)
    patch_store.upsert_record(body())
    for too_old in ({}, {"X-Store-Version-Code": "49"}, {"X-Store-Version-Code": "junk"}):
        r = latest(client, paid, **too_old)
        assert r.status_code == 426
        assert r.json() == {"detail": "Store update required", "min_store_version_code": 50}
    assert latest(client, paid, **{"X-Store-Version-Code": "50"}).status_code == 200


def test_latest_tells_a_store_that_only_meets_an_older_bundle_to_update(client, paid):
    add_bundle(patcher_version="1.0", min_store=0, sha="b" * 64)
    add_bundle(patcher_version="2.0", min_store=60, sha="c" * 64)
    patch_store.upsert_record(body(version_code=1000, patcher_version="1.0"))
    patch_store.upsert_record(body(version_code=1100, patcher_version="2.0", dex_sha256="e" * 64,
                                   apk_sha256="f" * 64))
    r = latest(client, paid, **{"X-Store-Version-Code": "10"})
    assert r.status_code == 426 and r.json()["min_store_version_code"] == 60
    r = latest(client, paid, **{"X-Store-Version-Code": "60"})
    assert r.status_code == 200 and r.json()["version_code"] == 1100


def test_latest_older_bundle_is_fine_when_it_has_the_newest_version(client, paid):
    add_bundle(patcher_version="1.0", min_store=0, sha="b" * 64)
    add_bundle(patcher_version="2.0", min_store=60, sha="c" * 64)
    patch_store.upsert_record(body(version_code=1100, patcher_version="1.0"))
    patch_store.upsert_record(body(version_code=1000, patcher_version="2.0", dex_sha256="e" * 64,
                                   apk_sha256="f" * 64))
    assert latest(client, paid, **{"X-Store-Version-Code": "10"}).json()["version_code"] == 1100


def test_latest_ignores_non_official_source_for_whatsapp(client, paid):
    add_bundle()
    patch_store.upsert_record(body(source="mirror.example"))
    assert latest(client, paid).status_code == 404


def test_artifacts_by_apk_hash_and_by_dex_hash(client, paid):
    add_bundle()
    rev = patch_store.upsert_record(body())
    base = "/api/patch/com.whatsapp/artifacts?patcher_version=1.0"
    for query in (f"&apk_sha256={'a' * 64}", f"&dex_sha256={'d' * 64}"):
        r = client.get(base + query, headers=paid)
        assert r.status_code == 200
        rec = r.json()
        assert set(rec) == set(body()) | {"revision"}
        assert rec["artifacts"] == {"k": "v"} and rec["source"] == "whatsapp.com"
        assert rec["revision"] >= rev
    assert client.get(base + f"&apk_sha256={'9' * 64}", headers=paid).json()["detail"] == "Unknown build"


def test_artifacts_requires_exactly_one_hash(client, paid):
    base = "/api/patch/com.whatsapp/artifacts?patcher_version=1.0"
    assert client.get(base, headers=paid).status_code == 422
    both = f"&apk_sha256={'a' * 64}&dex_sha256={'d' * 64}"
    assert client.get(base + both, headers=paid).status_code == 422
    assert client.get(base + "&apk_sha256=zz", headers=paid).status_code == 422
    assert client.get("/api/patch/com.whatsapp/artifacts?apk_sha256=" + "a" * 64,
                      headers=paid).status_code == 422


def test_bundle_download_streams_file(client, paid):
    add_bundle(data=b"PKbundle", sha="c" * 64)
    r = client.get("/api/patch/com.whatsapp/bundle/1.0", headers=paid)
    assert r.status_code == 200 and r.content == b"PKbundle"
    assert r.headers["content-type"] == "application/zip"
    assert r.headers["etag"].strip('"') == "c" * 64
    assert client.get("/api/patch/com.whatsapp/bundle/1.0").status_code == 401


def test_bundle_unknown_or_missing_file_is_404(client, paid):
    assert client.get("/api/patch/com.whatsapp/bundle/9.9", headers=paid).json()["detail"] == "Unknown bundle"
    path = add_bundle()
    path.unlink()
    assert client.get("/api/patch/com.whatsapp/bundle/1.0", headers=paid).status_code == 404


def test_bundle_rejects_bad_patcher_version(client, paid, tmp_path):
    (tmp_path / "bundles" / "x.zip").parent.mkdir(parents=True, exist_ok=True)
    (tmp_path / "bundles" / "x.zip").write_bytes(b"secret")
    for bad in ("..%2Fx", "a%2Fb", "..", ".x", "a b"):
        r = client.get(f"/api/patch/com.whatsapp/bundle/{bad}", headers=paid)
        assert r.status_code in (404, 422) and b"secret" not in r.content


def test_unknown_package_is_404(client, paid):
    for url in ("/latest", f"/artifacts?patcher_version=1.0&apk_sha256={'a' * 64}", "/bundle/1.0"):
        r = client.get("/api/patch/com.nope" + url, headers=paid)
        assert r.status_code == 404 and r.json()["detail"] == "Unknown package"


def test_rate_limited_per_account(client, fake_stripe, monkeypatch):
    fake_stripe.add_customer("cus_bob", "bob@example.com")
    fake_stripe.add_subscription("cus_bob", "active")
    monkeypatch.setattr(ratelimit.patch_rate_limit, "limit", 3)
    alice = bearer(make_user("alice", "cus_paid"))
    bob = bearer(make_user("bob", "cus_bob"))
    assert [latest(client, alice).status_code for _ in range(3)] == [404] * 3
    r = latest(client, alice)
    assert r.status_code == 429 and int(r.headers["retry-after"]) >= 1
    assert latest(client, bob).status_code == 404
