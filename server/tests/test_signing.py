"""apps_utils.sign_apk on Stitch's fastsigner.

Most tests capture the command instead of running it. One runs the real binary
against a throwaway zip with Stitch's bundled debug key, so a change in
fastsigner's CLI cannot pass here unnoticed.
"""
import os
import subprocess
import zipfile
from pathlib import Path

import pytest

from utils import apps_utils

KEYSTORE_VARS = ("KEYSTORE_PATH", "KEY_ALIAS", "KEYSTORE_PASSWORD", "KEY_PASSWORD")
# The v2/v3 signing block's magic, which sits just before the central directory.
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"


@pytest.fixture
def no_keystore(monkeypatch):
    for name in KEYSTORE_VARS:
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def keystore(monkeypatch):
    monkeypatch.setenv("KEYSTORE_PATH", "/keys/release.jks")
    monkeypatch.setenv("KEY_ALIAS", "key0")
    monkeypatch.setenv("KEYSTORE_PASSWORD", "store-secret")
    monkeypatch.setenv("KEY_PASSWORD", "key-secret")


@pytest.fixture
def captured(monkeypatch):
    """Records each fastsigner invocation instead of running it."""
    calls = []

    def fake_run(args, **kwargs):
        calls.append((args, kwargs))
        return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

    monkeypatch.setattr(apps_utils.subprocess, "run", fake_run)
    return calls


def make_zip(path: Path, name="classes.dex") -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(name, b"dex\n035\x00" + b"\x00" * 64)
    return path


def test_passwords_are_referenced_by_env_never_passed_as_values(keystore, captured, tmp_path):
    apps_utils.sign_apk(None, tmp_path / "in.apk", tmp_path / "out.apk", False)

    (args, kwargs), = captured
    assert args[0] == str(apps_utils.FASTSIGNER_PATH)
    assert args[args.index("--ks") + 1] == "/keys/release.jks"
    assert args[args.index("--ks-key-alias") + 1] == "key0"
    assert args[args.index("--ks-pass") + 1] == "env:KEYSTORE_PASSWORD"
    assert args[args.index("--key-pass") + 1] == "env:KEY_PASSWORD"
    assert not any("secret" in arg for arg in args)
    # The child reads the passwords from the environment it inherits.
    assert kwargs.get("env") is None
    assert kwargs["stdin"] is subprocess.DEVNULL


def test_key_password_defaults_to_the_keystore_password(keystore, monkeypatch, captured, tmp_path):
    monkeypatch.delenv("KEY_PASSWORD")
    apps_utils.sign_apk(None, tmp_path / "in.apk", tmp_path / "out.apk", False)
    (args, _), = captured
    assert "--key-pass" not in args


def test_without_a_keystore_stitchs_debug_key_is_used(no_keystore, captured, tmp_path):
    apps_utils.sign_apk(None, tmp_path / "in.apk", tmp_path / "out.apk", False)
    (args, _), = captured
    assert args[args.index("--key") + 1] == str(apps_utils.DEBUG_KEY_PATH)
    assert args[args.index("--cert") + 1] == str(apps_utils.DEBUG_CERT_PATH)
    assert "--ks" not in args


def test_a_plain_apk_is_signed_into_the_output_path(no_keystore, captured, tmp_path):
    apps_utils.sign_apk(None, tmp_path / "in.apk", tmp_path / "out.apk", False)
    (args, _), = captured
    assert args[args.index("--out") + 1] == str(tmp_path / "out.apk")
    assert args[-1] == str(tmp_path / "in.apk")


def test_a_bundle_is_signed_in_place_in_one_call(no_keystore, captured, tmp_path):
    base = make_zip(tmp_path / "base.apk")
    split = make_zip(tmp_path / "config.en.apk")

    apps_utils.sign_apk(tmp_path, base, None, True)

    (args, _), = captured
    assert "--out" not in args and "--out-dir" not in args
    targets = args[args.index(str(apps_utils.DEBUG_CERT_PATH)) + 1:]
    # Passed once, although the main apk is also one of the globbed splits.
    assert sorted(targets) == sorted([str(base.resolve()), str(split.resolve())])


def test_a_nested_main_apk_is_still_signed(no_keystore, captured, tmp_path):
    nested = tmp_path / "apks"
    nested.mkdir()
    main = make_zip(nested / "com.example.apk")
    split = make_zip(tmp_path / "config.en.apk")

    apps_utils.sign_apk(tmp_path, main, None, True)

    (args, _), = captured
    assert str(main.resolve()) in args
    assert str(split.resolve()) in args


def test_a_failure_raises_without_leaking_a_password(keystore, monkeypatch, tmp_path):
    def failing_run(args, **kwargs):
        return subprocess.CompletedProcess(args, 1, stdout="", stderr="wrong password")

    monkeypatch.setattr(apps_utils.subprocess, "run", failing_run)
    with pytest.raises(subprocess.CalledProcessError) as excinfo:
        apps_utils.sign_apk(None, tmp_path / "in.apk", tmp_path / "out.apk", False)
    assert "secret" not in str(excinfo.value)


@pytest.mark.skipif(not os.access(apps_utils.FASTSIGNER_PATH, os.X_OK),
                    reason="fastsigner binary is not executable here")
def test_the_real_fastsigner_signs_with_the_debug_key(no_keystore, tmp_path):
    unsigned = make_zip(tmp_path / "in.apk")
    signed = tmp_path / "out.apk"

    apps_utils.sign_apk(None, unsigned, signed, False)

    assert APK_SIG_BLOCK_MAGIC in signed.read_bytes()
    with zipfile.ZipFile(signed) as archive:
        assert archive.testzip() is None
