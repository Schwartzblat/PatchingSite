"""manage.py doctor: the on-device patching settings are advisory, except an unwritable bundle dir."""
import os

import pytest

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
