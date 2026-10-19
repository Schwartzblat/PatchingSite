"""The Works-with list (hand-edited, validated) and GET /api/generic."""
import json
import logging

import pytest
from starlette.testclient import TestClient

import config
from utils import works_with
from utils.works_with import WorksWithError

from tests.test_doctor_patching import configured, warn_lines
from tests.test_manage import doctor

ENTRY = {"package": "com.example.app", "name": "Example", "note": "Ads gone",
         "tested_version": "1.2.3", "tested_on": "2026-10-18"}


def text(*entries):
    return json.dumps(list(entries))


def test_parse_accepts_a_valid_list():
    second = dict(ENTRY, package="org.other.thing_2", note="x" * 200)
    assert works_with.parse(text(ENTRY, second)) == [ENTRY, second]


@pytest.mark.parametrize("raw", [
    json.dumps({"package": "com.example.app"}),
    text(ENTRY, {k: v for k, v in ENTRY.items() if k != "note"}),
    text(ENTRY, dict(ENTRY, package="com.other.app", extra="x")),
    text(ENTRY, dict(ENTRY, package="com.other.app", name="")),
    text(ENTRY, dict(ENTRY, package="not a package")),
    text(ENTRY, dict(ENTRY, package="com.other.app", tested_on="18/10/2026")),
    text(ENTRY, dict(ENTRY, package="com.other.app", note="x" * 201)),
    text(ENTRY, dict(ENTRY)),
], ids=["not-a-list", "missing-key", "extra-key", "empty-value", "bad-package",
        "bad-date", "long-note", "duplicate"])
def test_parse_rejects(raw):
    with pytest.raises(WorksWithError) as err:
        works_with.parse(raw)
    if raw.startswith("["):
        assert "entry 1" in str(err.value)


@pytest.fixture
def client(tmp_db, fake_stripe, tmp_path, monkeypatch):
    import main

    monkeypatch.setattr(config, "BUNDLES_DIR", tmp_path / "bundles")
    with TestClient(main.app) as test_client:
        yield test_client


def test_generic_status_public(client, tmp_path, monkeypatch):
    response = client.get("/api/generic")
    assert response.status_code == 200
    assert response.json() == {"enabled": False, "works_with": []}

    path = tmp_path / "ww.json"
    path.write_text(text(ENTRY))
    monkeypatch.setattr(config, "GENERIC_WORKS_WITH_PATH", path)
    monkeypatch.setattr(config, "GENERIC_PATCHING", True)
    assert client.get("/api/generic").json() == {"enabled": True, "works_with": [ENTRY]}


def test_broken_works_with_file_serves_empty_list(client, tmp_path, monkeypatch, caplog):
    path = tmp_path / "ww.json"
    path.write_text("{not json")
    monkeypatch.setattr(config, "GENERIC_WORKS_WITH_PATH", path)
    caplog.set_level(logging.ERROR)

    for _ in range(2):
        response = client.get("/api/generic")
        assert response.status_code == 200
        assert response.json()["works_with"] == []
    assert len([r for r in caplog.records if "works-with" in r.getMessage().lower()]) == 1

    path.write_text(text(ENTRY) + "\n")
    assert client.get("/api/generic").json()["works_with"] == [ENTRY]


def test_shipped_file_is_valid():
    shipped = config.GENERIC_WORKS_WITH_PATH.read_text()
    assert works_with.parse(shipped) == []


def test_doctor_flags_broken_works_with(monkeypatch, capsys, tmp_path):
    path = tmp_path / "ww.json"
    path.write_text(text(ENTRY))
    _, out = doctor(monkeypatch, capsys, **configured(tmp_path, GENERIC_WORKS_WITH_PATH=path))
    assert any("works-with" in line.lower() and "1 entry" in line and "ok" in line.lower()
               for line in out.splitlines())

    path.write_text(text(dict(ENTRY, tested_on="nope")))
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path, GENERIC_WORKS_WITH_PATH=path))
    assert code == 1
    assert any("works-with" in line.lower() and "FAIL" in line and "entry 0" in line
               for line in out.splitlines())


def test_doctor_warns_switch_on_without_bundle(tmp_db, monkeypatch, capsys, tmp_path):
    code_off, off = doctor(monkeypatch, capsys, **configured(tmp_path, GENERIC_PATCHING=False))
    code, out = doctor(monkeypatch, capsys, **configured(tmp_path, GENERIC_PATCHING=True))
    assert len(warn_lines(out, "GENERIC_PATCHING")) == 1
    assert not warn_lines(off, "GENERIC_PATCHING")
    assert code == code_off  # advisory only
