import base64
import importlib

import pytest

import config as _config_at_import


@pytest.fixture
def reload_config(monkeypatch):
    """Reload `config` under the env the test sets; reload it clean afterwards."""

    def _reload():
        return importlib.reload(_config_at_import)

    yield _reload
    monkeypatch.undo()
    importlib.reload(_config_at_import)


def test_on_device_packages_parses_comma_list(monkeypatch, reload_config):
    monkeypatch.setenv("ON_DEVICE_PACKAGES", "com.whatsapp, com.tranzmate")
    assert reload_config().ON_DEVICE_PACKAGES == frozenset({"com.whatsapp", "com.tranzmate"})
    monkeypatch.delenv("ON_DEVICE_PACKAGES")
    assert reload_config().ON_DEVICE_PACKAGES == frozenset()


def test_master_key_must_be_32_bytes(monkeypatch, reload_config):
    monkeypatch.setenv("SIGNING_KEYS_MASTER_KEY", base64.b64encode(b"x" * 31).decode())
    with pytest.raises(RuntimeError) as exc:  # reload rebinds ConfigError, so match by name
        reload_config()
    assert type(exc.value).__name__ == "ConfigError"
    monkeypatch.setenv("SIGNING_KEYS_MASTER_KEY", "not base64 !!")
    with pytest.raises(RuntimeError) as exc:  # reload rebinds ConfigError, so match by name
        reload_config()
    assert type(exc.value).__name__ == "ConfigError"
    monkeypatch.setenv("SIGNING_KEYS_MASTER_KEY", base64.b64encode(b"k" * 32).decode())
    key = reload_config().SIGNING_KEYS_MASTER_KEY
    assert isinstance(key, bytes) and len(key) == 32
    monkeypatch.delenv("SIGNING_KEYS_MASTER_KEY")
    assert reload_config().SIGNING_KEYS_MASTER_KEY is None


def test_paths_default_under_data_dir(monkeypatch, reload_config):
    monkeypatch.delenv("BUNDLES_DIR", raising=False)
    monkeypatch.delenv("BUNDLE_PUBLIC_KEY_PATH", raising=False)
    cfg = reload_config()
    assert cfg.BUNDLES_DIR == cfg.DATA_DIR / "bundles"
    assert cfg.BUNDLE_PUBLIC_KEY_PATH == cfg.DATA_DIR / "bundle-signing.pub.pem"
