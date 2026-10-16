import database
from utils import patch_store


def record(**over):
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


def bundle(package="com.whatsapp", patcher_version="1.0", min_store_version_code=0, min_sdk=26):
    return {"package": package, "patcher_version": patcher_version,
            "min_store_version_code": min_store_version_code, "min_sdk": min_sdk}


def put_bundle(**over):
    meta = bundle(**over)
    return patch_store.upsert_bundle(meta, "b" * 64, 123)


def test_init_is_idempotent(tmp_db):
    database.init()
    database.init()
    conn = database._connect()
    try:
        for table in ("patch_records", "patch_bundles", "signing_keys"):
            assert conn.execute(f"PRAGMA table_info({table})").fetchall(), table
    finally:
        conn.close()


def test_upsert_and_find_by_both_hashes(tmp_db):
    rec = record()
    seq = patch_store.upsert_record(rec)
    by_apk = patch_store.find_record("com.whatsapp", "1.0", apk_sha256="a" * 64)
    by_dex = patch_store.find_record("com.whatsapp", "1.0", dex_sha256="d" * 64)
    assert by_apk == by_dex
    assert by_apk["revision"] == seq
    assert {k: v for k, v in by_apk.items() if k != "revision"} == rec
    assert patch_store.find_record("com.whatsapp", "1.0", apk_sha256="x" * 64) is None
    assert patch_store.find_record("com.whatsapp", "2.0", dex_sha256="d" * 64) is None


def test_reposting_identical_record_keeps_revision(tmp_db):
    first = patch_store.upsert_record(record())
    assert patch_store.upsert_record(record()) == first
    changed = patch_store.upsert_record(record(artifacts={"k": "w"}))
    assert changed > first
    assert patch_store.find_record("com.whatsapp", "1.0", dex_sha256="d" * 64)["artifacts"] == {"k": "w"}


def test_revision_includes_bundle_seq(tmp_db):
    seq = patch_store.upsert_record(record())
    bundle_seq = put_bundle()
    assert bundle_seq > seq
    found = patch_store.find_record("com.whatsapp", "1.0", dex_sha256="d" * 64)
    assert found["revision"] == bundle_seq
    assert patch_store.get_bundle("com.whatsapp", "1.0")["seq"] == bundle_seq
    assert patch_store.get_bundle("com.whatsapp", "9") is None


def test_bundle_reupload_gets_new_seq(tmp_db):
    assert put_bundle() < put_bundle()


def test_latest_prefers_highest_version_code_then_newest_bundle(tmp_db):
    patch_store.upsert_record(record(version_code=1, dex_sha256="1" * 64, patcher_version="1.0"))
    patch_store.upsert_record(record(version_code=2, dex_sha256="2" * 64, patcher_version="1.0"))
    patch_store.upsert_record(record(version_code=2, dex_sha256="3" * 64, patcher_version="2.0"))
    put_bundle(patcher_version="1.0")
    put_bundle(patcher_version="2.0")
    best = patch_store.latest_record("com.whatsapp", 0, None)
    assert best["version_code"] == 2 and best["patcher_version"] == "2.0"
    put_bundle(patcher_version="1.0")  # re-upload: now the newest bundle
    best = patch_store.latest_record("com.whatsapp", 0, None)
    assert best["patcher_version"] == "1.0" and best["dex_sha256"] == "2" * 64


def test_latest_skips_unsupported_and_other_sources(tmp_db):
    patch_store.upsert_record(record(version_code=5, dex_sha256="5" * 64, supported=False))
    patch_store.upsert_record(record(version_code=4, dex_sha256="4" * 64, source="other.com"))
    patch_store.upsert_record(record(version_code=3, dex_sha256="3" * 64))
    put_bundle()
    assert patch_store.latest_record("com.whatsapp", 0, "whatsapp.com")["version_code"] == 3
    assert patch_store.latest_record("com.whatsapp", 0, None)["version_code"] == 4
    assert patch_store.latest_record("com.other", 0, None) is None


def test_latest_requires_matching_bundle(tmp_db):
    patch_store.upsert_record(record())
    assert patch_store.latest_record("com.whatsapp", 0, None) is None
    put_bundle(patcher_version="9.9")
    assert patch_store.latest_record("com.whatsapp", 0, None) is None
    put_bundle()
    assert patch_store.latest_record("com.whatsapp", 0, None) is not None


def test_latest_respects_min_store_version(tmp_db):
    patch_store.upsert_record(record())
    put_bundle(min_store_version_code=500)
    assert patch_store.latest_record("com.whatsapp", 499, None) is None
    assert patch_store.latest_record("com.whatsapp", 500, None)["version_code"] == 1000


def test_signing_key_insert_is_first_writer_wins(tmp_db):
    conn = database._connect()
    try:
        conn.execute("INSERT INTO users (id, username, email, password_hash, password_salt) "
                     "VALUES (7, 'u7', 'u7@example.com', 'h', 's')")
    finally:
        conn.close()
    assert patch_store.get_signing_key(7) is None
    patch_store.insert_signing_key_if_absent(7, b"n1", b"c1", "s256-1", "s1-1")
    patch_store.insert_signing_key_if_absent(7, b"n2", b"c2", "s256-2", "s1-2")
    row = patch_store.get_signing_key(7)
    assert row["nonce"] == b"n1" and row["cert_sha256"] == "s256-1"
