"""manage.py doctor: the on-device patching settings are advisory, except an unwritable bundle dir."""
import os
import sys

import pytest

import config

from tests.test_manage import SMTP_ON, doctor

BASE = dict(APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_ON)


def configured(tmp_path, **overrides):
    key = tmp_path / "bundle.pub.pem"
    key.write_text("pem")
    settings = dict(BASE, CRAWLER_SECRET="crawler-secret-value", BUNDLE_UPLOAD_SECRET="upload-secret-value",
                    SIGNING_KEYS_MASTER_KEY=b"\x01" * 32, BUNDLE_PUBLIC_KEY_PATH=key,
                    BUNDLES_DIR=tmp_path / "bundles")
    settings.update(overrides)
    return settings


def warn_lines(out, name):
    return [line for line in out.splitlines() if name in line and "warn" in line]


def test_doctor_warns_on_missing_patch_secrets(monkeypatch, capsys, tmp_path):
    code_on, out_on = doctor(monkeypatch, capsys, **configured(tmp_path))
    code_off, out = doctor(monkeypatch, capsys, **configured(
        tmp_path, CRAWLER_SECRET=None, BUNDLE_UPLOAD_SECRET=None, SIGNING_KEYS_MASTER_KEY=None,
        BUNDLE_PUBLIC_KEY_PATH=tmp_path / "missing.pem"))

    assert code_off == code_on
    for name in ("CRAWLER_SECRET", "BUNDLE_UPLOAD_SECRET", "SIGNING_KEYS_MASTER_KEY", "BUNDLE_PUBLIC_KEY_PATH"):
        assert len(warn_lines(out, name)) == 1, name
        assert not warn_lines(out_on, name), name
    assert "crawler-secret-value" not in out_on and "upload-secret-value" not in out_on


def test_an_absent_bundles_dir_is_fine(monkeypatch, capsys, tmp_path):
    code_ok, _ = doctor(monkeypatch, capsys, **configured(tmp_path))
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path, BUNDLES_DIR=tmp_path / "nope"))
    assert code == code_ok
    assert not warn_lines(out, "BUNDLES_DIR")


@pytest.mark.skipif(os.geteuid() == 0, reason="root can write anywhere")
def test_an_existing_unwritable_bundles_dir_fails(monkeypatch, capsys, tmp_path):
    ro = tmp_path / "ro"
    ro.mkdir()
    ro.chmod(0o555)
    try:
        code, out = doctor(monkeypatch, capsys, **configured(tmp_path, BUNDLES_DIR=ro))
    finally:
        ro.chmod(0o755)
    assert code == 1
    assert any("BUNDLES_DIR" in line and "not writable" in line for line in out.splitlines())


def test_a_bundles_dir_that_is_a_file_fails(monkeypatch, capsys, tmp_path):
    plain = tmp_path / "bundles-file"
    plain.write_text("x")
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path, BUNDLES_DIR=plain))
    assert code == 1
    assert any("BUNDLES_DIR" in line and "not writable" in line for line in out.splitlines())


def test_a_missing_stitch_bundle_module_warns(monkeypatch, capsys, tmp_path):
    code_ok, _ = doctor(monkeypatch, capsys, **configured(tmp_path))
    monkeypatch.setitem(sys.modules, "stitch.bundle", None)
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path))
    assert code == code_ok
    assert len(warn_lines(out, "stitch.bundle")) == 1


def test_wrong_master_key_for_stored_signing_keys_warns(monkeypatch, capsys, tmp_path, tmp_db):
    from utils import signing_keys
    import database

    database.register_user(database.RegisterRequest(
        username="alice", email="a@example.com", password="correct horse battery"))
    uid = database.get_user("alice").id
    monkeypatch.setattr(config, "SIGNING_KEYS_MASTER_KEY", b"\x01" * 32)
    signing_keys.get_or_create(uid)

    code_ok, out_ok = doctor(monkeypatch, capsys, **configured(tmp_path))
    assert not warn_lines(out_ok, "SIGNING_KEYS_MASTER_KEY")
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path, SIGNING_KEYS_MASTER_KEY=b"\x02" * 32))
    assert code == code_ok
    assert len(warn_lines(out, "does not match")) == 1


def test_on_device_packages_are_checked(monkeypatch, capsys, tmp_path, tmp_db):
    from utils import patch_store
    from tests.test_patch_store import bundle, record

    code_ok, _ = doctor(monkeypatch, capsys, **configured(tmp_path))
    code, out = doctor(monkeypatch, capsys, **configured(
        tmp_path, ON_DEVICE_PACKAGES=frozenset({"com.nonexistent", "com.whatsapp"})))
    assert code == code_ok
    assert len(warn_lines(out, "com.nonexistent")) == 1
    assert len(warn_lines(out, "com.whatsapp")) == 1  # nothing advertisable yet

    patch_store.upsert_bundle(bundle(min_store_version_code=10**6), "b" * 64, 1)
    patch_store.upsert_record(record())
    _, out = doctor(monkeypatch, capsys, **configured(
        tmp_path, ON_DEVICE_PACKAGES=frozenset({"com.whatsapp"})))
    assert not warn_lines(out, "com.whatsapp")
