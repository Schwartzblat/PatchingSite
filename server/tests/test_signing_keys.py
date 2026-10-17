"""Per-user signing keys: the key a user's phone signs their patched apps with.

Losing or regenerating one forces the user through a full chat move, so the
first key stored for a user must win and stay for good.
"""
import base64
import hashlib
import importlib.util
import shutil
import subprocess
import threading
import zipfile
from pathlib import Path

import pytest
from cryptography.exceptions import InvalidTag
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID
from starlette.testclient import TestClient

import config
import database
from tests.bundle_helpers import build_module_apk
from utils import jwt_utils, patch_store, signing_keys

# The fastsigner Stitch ships (and the server signs with), and apksigner when on PATH.
_STITCH = importlib.util.find_spec("stitch")
FASTSIGNER = Path(_STITCH.origin).parent / "bin" / "fastsigner" if _STITCH and _STITCH.origin else Path("/nonexistent")
APKSIGNER = Path(shutil.which("apksigner") or "/nonexistent")


def make_user(username):
    assert database.register_user(database.RegisterRequest(
        username=username, email=f"{username}@example.com", password="correct horse battery"))
    return database.get_user(username)


@pytest.fixture
def users(tmp_db):
    return make_user("alice"), make_user("bob")


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


def bearer(user):
    token = jwt_utils.create_access_token(user, client=jwt_utils.TOKEN_CLIENT_MOBILE)
    return {"Authorization": f"Bearer {token}"}


def test_generated_pkcs12_loads_with_password():
    key = signing_keys.generate(42)
    private, cert, extra = pkcs12.load_key_and_certificates(key.pkcs12, key.password.encode())
    assert isinstance(private.curve, ec.SECP256R1)
    assert extra == []
    assert "42" in cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value
    assert cert.subject == cert.issuer
    assert key.cert_sha256 == hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    assert key.cert_sha1 == hashlib.sha1(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    assert cert.not_valid_after_utc.year == 2056


def test_seal_round_trip_and_aad_binds_user():
    key = signing_keys.generate(1)
    nonce, ciphertext = signing_keys.seal(1, key)
    assert len(nonce) == 12
    assert signing_keys.open_sealed(1, nonce, ciphertext) == key
    with pytest.raises(InvalidTag):
        signing_keys.open_sealed(2, nonce, ciphertext)


def test_signing_key_is_stable_per_user(users):
    alice, _ = users
    first = signing_keys.get_or_create(alice.id)
    second = signing_keys.get_or_create(alice.id)
    assert first.cert_sha256 == second.cert_sha256
    assert first.password == second.password


def test_signing_keys_differ_between_users(users):
    alice, bob = users
    assert signing_keys.get_or_create(alice.id).cert_sha256 != signing_keys.get_or_create(bob.id).cert_sha256


def test_signing_key_concurrent_first_requests_agree(users, monkeypatch):
    alice, _ = users
    barrier = threading.Barrier(2, timeout=10)
    real_generate = signing_keys.generate

    def generate_after_both_read(user_id):
        # Both threads have read "no row" before either one inserts.
        barrier.wait()
        return real_generate(user_id)

    monkeypatch.setattr(signing_keys, "generate", generate_after_both_read)
    results = []

    def worker():
        results.append(signing_keys.get_or_create(alice.id).cert_sha256)

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(results) == 2 and results[0] == results[1]
    conn = database._connect()
    try:
        assert conn.execute("SELECT COUNT(*) FROM signing_keys").fetchone()[0] == 1
    finally:
        conn.close()
    assert patch_store.get_signing_key(alice.id)["cert_sha256"] == results[0]


def test_endpoint_requires_login(client):
    assert client.get("/api/signing-key").status_code == 401


def test_endpoint_without_master_key_is_503(client, users, monkeypatch):
    monkeypatch.setattr(config, "SIGNING_KEYS_MASTER_KEY", None)
    assert client.get("/api/signing-key", headers=bearer(users[0])).status_code == 503


def test_endpoint_returns_no_store(client, users):
    alice, _ = users
    response = client.get("/api/signing-key", headers=bearer(alice))
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    body = response.json()
    assert set(body) == {"pkcs12", "password", "alias", "cert_sha256", "cert_sha1"}
    assert body["alias"] == "androidmod"
    _, cert, _ = pkcs12.load_key_and_certificates(base64.b64decode(body["pkcs12"]), body["password"].encode())
    assert body["cert_sha256"] == hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    again = client.get("/api/signing-key", headers=bearer(alice)).json()
    assert again["cert_sha256"] == body["cert_sha256"] and again["password"] == body["password"]


def test_endpoint_is_rate_limited_per_account(client, users):
    alice, bob = users
    codes = [client.get("/api/signing-key", headers=bearer(alice)).status_code for _ in range(21)]
    assert codes[:20] == [200] * 20 and codes[20] == 429
    limited = client.get("/api/signing-key", headers=bearer(alice))
    assert int(limited.headers["retry-after"]) > 0
    # Same client address, different account: its own quota.
    assert [client.get("/api/signing-key", headers=bearer(bob)).status_code for _ in range(20)] == [200] * 20


def test_unauthenticated_requests_do_not_spend_an_accounts_quota(client, users):
    for _ in range(30):
        assert client.get("/api/signing-key").status_code == 401
    assert client.get("/api/signing-key", headers=bearer(users[0])).status_code == 200


def test_endpoint_keys_differ_between_users(client, users):
    alice, bob = users
    a = client.get("/api/signing-key", headers=bearer(alice)).json()
    b = client.get("/api/signing-key", headers=bearer(bob)).json()
    assert a["cert_sha256"] != b["cert_sha256"]


@pytest.mark.skipif(not FASTSIGNER.exists(), reason="fastsigner not present")
def test_fastsigner_signs_with_generated_key(tmp_path):
    key = signing_keys.generate(7)
    p12 = tmp_path / "key.p12"
    p12.write_bytes(key.pkcs12)
    apk = build_module_apk(tmp_path / "in.apk", '<uses-sdk android:minSdkVersion="26"/><application/>')
    with zipfile.ZipFile(apk, "a") as zf:
        zf.writestr("classes.dex", b"dex\n035\0" + bytes(100))
    signed = tmp_path / "signed.apk"
    result = subprocess.run([str(FASTSIGNER), "--ks", str(p12), "--ks-pass", "stdin", "--out", str(signed), str(apk)],
                            input=key.password + "\n", capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr.replace(key.password, "<pw>")
    assert signed.exists()
    if APKSIGNER.exists() and shutil.which("java"):
        verify = subprocess.run([str(APKSIGNER), "verify", "--print-certs", str(signed)],
                                capture_output=True, text=True, timeout=120)
        assert verify.returncode == 0, verify.stderr
        assert key.cert_sha256 in verify.stdout
