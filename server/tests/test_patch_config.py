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


def test_db_path_defaults_into_data_dir_and_can_be_set_apart(monkeypatch, reload_config, tmp_path):
    monkeypatch.setenv("SERVER_DATA_DIR", str(tmp_path / "v2"))
    monkeypatch.delenv("DB_PATH", raising=False)
    assert reload_config().DB_PATH == tmp_path / "v2" / "users.db"
    monkeypatch.setenv("DB_PATH", str(tmp_path / "live" / "users.db"))
    config = reload_config()
    assert config.DB_PATH == tmp_path / "live" / "users.db"
    assert config.DOWNLOAD_CACHE_DIR == tmp_path / "v2" / "download_cache"
