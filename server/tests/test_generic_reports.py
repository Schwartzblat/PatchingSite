"""POST /api/generic/reports, the admin listing and manage.py generic-reports.

A report is stored without the account or the IP, and its date only to the day.
"""
import argparse
import logging
import sqlite3
from datetime import datetime, timezone

import pytest
from starlette.testclient import TestClient

import config
import database
from tests.test_patch_read_api import PW, bearer, make_user

PACKAGE = "com.example.secretapp"
COLUMNS = ["id", "created_on", "package", "app_name", "version_name", "version_code",
           "worked", "note", "store_version_code", "android_sdk"]


@pytest.fixture
def client(tmp_db, fake_stripe, monkeypatch):
    import main

    monkeypatch.setattr(config, "GENERIC_PATCHING", True)
    monkeypatch.setattr(config, "ADMIN_USERNAMES", frozenset({"owner"}))
    fake_stripe.add_customer("cus_paid", "alice@example.com")
    fake_stripe.add_subscription("cus_paid", "active")
    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def paid(client):
    return bearer(make_user("alice", "cus_paid"))


def body(**overrides):
    data = {"package": PACKAGE, "app_name": "Secret App", "version_name": "1.2.3", "version_code": 123,
            "worked": True, "note": "ads gone", "store_version_code": 7, "android_sdk": 34}
    data.update(overrides)
    return data


def send(client, headers, **overrides):
    return client.post("/api/generic/reports", json=body(**overrides), headers=headers)


def rows(db_path):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        return [dict(row) for row in conn.execute("SELECT * FROM generic_reports ORDER BY id")]
    finally:
        conn.close()


def test_report_is_stored_without_account_or_ip(client, paid, tmp_db):
    r = send(client, paid)
    assert r.status_code == 201
    assert r.json() == {"message": "Thanks! Your report was sent."}

    conn = sqlite3.connect(tmp_db)
    try:
        assert [c[1] for c in conn.execute("PRAGMA table_info(generic_reports)")] == COLUMNS
    finally:
        conn.close()
    [row] = rows(tmp_db)
    values = " ".join(str(v) for v in row.values()).lower()
    for secret in ("alice", "alice@example.com", "cus_paid", "testclient", "127.0.0.1"):
        assert secret not in values
    assert row["created_on"] == datetime.now(timezone.utc).strftime("%Y-%m-%d")
    assert row["package"] == PACKAGE and row["worked"] == 1 and row["note"] == "ads gone"
    assert row["version_code"] == 123 and row["store_version_code"] == 7 and row["android_sdk"] == 34


def test_report_logs_name_no_package_or_account(client, paid, caplog):
    with caplog.at_level(logging.DEBUG):
        caplog.clear()
        assert send(client, paid).status_code == 201
    assert [r.getMessage() for r in caplog.records if "Generic report stored" in r.getMessage()] == [
        "Generic report stored"]
    text = "\n".join(record.getMessage() for record in caplog.records).lower()
    for secret in (PACKAGE, "alice", "alice@example.com", "testclient", "127.0.0.1"):
        assert secret not in text


def test_note_is_stripped_and_blank_becomes_null(client, paid, tmp_db):
    assert send(client, paid, note="   ").status_code == 201
    assert send(client, paid, note="  hi  ", app_name="  Name  ").status_code == 201
    first, second = rows(tmp_db)
    assert first["note"] is None
    assert second["note"] == "hi" and second["app_name"] == "Name"
    body_without_note = {key: value for key, value in body().items() if key != "note"}
    assert client.post("/api/generic/reports", json=body_without_note, headers=paid).status_code == 201


@pytest.mark.parametrize("overrides", [
    {"extra": "field"},
    {"worked": 1},
    {"note": "x" * 501},
    {"package": "bad"},
    {"package": "com.example.app\n"},
    {"app_name": ""},
    {"android_sdk": 0},
    {"version_code": -1},
    {"version_code": 2**63},
    {"store_version_code": 2**63},
    {"version_name": "  "},
])
def test_bad_reports_rejected_and_not_stored(client, paid, tmp_db, overrides):
    assert send(client, paid, **overrides).status_code == 422
    assert rows(tmp_db) == []


def test_report_needs_a_subscriber(client, tmp_db):
    assert client.post("/api/generic/reports", json=body()).status_code == 401
    unpaid = bearer(make_user("bob"))
    assert send(client, unpaid).status_code == 402
    assert rows(tmp_db) == []


def test_report_rate_limited_per_account(client, paid, tmp_db):
    assert [send(client, paid).status_code for _ in range(10)] == [201] * 10
    r = send(client, paid)
    assert r.status_code == 429 and int(r.headers["retry-after"]) >= 1
    assert len(rows(tmp_db)) == 10


def test_reports_404_when_off(client, paid, tmp_db, monkeypatch):
    monkeypatch.setattr(config, "GENERIC_PATCHING", False)
    r = send(client, paid)
    assert r.status_code == 404 and r.json()["detail"] == "Generic patching is off"
    assert rows(tmp_db) == []


def test_admin_lists_reports_newest_first(client, paid):
    for index in range(3):
        assert send(client, paid, app_name=f"App {index}").status_code == 201

    make_user("owner")
    assert client.get("/api/admin/generic-reports").status_code == 401
    assert client.post("/api/login", json={"username": "owner", "password": PW}).status_code == 200
    data = client.get("/api/admin/generic-reports?limit=2").json()
    assert data["total"] == 3
    assert [r["app_name"] for r in data["reports"]] == ["App 2", "App 1"]
    assert set(data["reports"][0]) == set(COLUMNS)
    assert client.get("/api/admin/generic-reports?limit=0").status_code == 422
    assert client.get("/api/admin/generic-reports?limit=501").status_code == 422
    assert client.get("/api/admin/generic-reports").json()["total"] == 3


def test_non_admin_gets_403(client):
    make_user("carol")
    assert client.post("/api/login", json={"username": "carol", "password": PW}).status_code == 200
    assert client.get("/api/admin/generic-reports").status_code == 403


def test_manage_generic_reports_prints_newest_first(client, paid, capsys):
    import manage

    for index in range(3):
        assert send(client, paid, app_name=f"App {index}", note=f"note {index}").status_code == 201

    assert manage.cmd_generic_reports(argparse.Namespace(limit=2)) == 0

    out = capsys.readouterr().out
    assert "App 2" in out and "App 1" in out and "App 0" not in out
    assert out.index("App 2") < out.index("App 1")
    assert PACKAGE in out and "note 2" in out


def test_manage_generic_reports_copes_with_no_table(monkeypatch, tmp_path, capsys):
    import manage

    path = tmp_path / "empty.db"
    sqlite3.connect(path).close()
    monkeypatch.setattr(config, "DB_PATH", path)
    assert manage.cmd_generic_reports(argparse.Namespace(limit=5)) == 0
    assert "generic_reports" in capsys.readouterr().out


def test_table_added_without_touching_others(tmp_db):
    def layout():
        conn = sqlite3.connect(tmp_db)
        try:
            names = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")]
            return {n: [tuple(c) for c in conn.execute(f'PRAGMA table_info("{n}")')] for n in names}
        finally:
            conn.close()

    database.init()
    before = layout()
    assert "generic_reports" in before
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP TABLE generic_reports")
    conn.commit()
    conn.close()
    database.init()
    after = layout()
    assert "generic_reports" in after
    assert after == before


def test_ip_limit_warning_does_not_log_the_ip(client, paid, tmp_db, caplog, monkeypatch):
    from utils import ratelimit
    monkeypatch.setattr(ratelimit.generic_report_ip_rate_limit, "limit", 2)
    with caplog.at_level(logging.WARNING):
        statuses = [send(client, paid).status_code for _ in range(3)]
    assert statuses == [201, 201, 429]
    warnings = [r.getMessage() for r in caplog.records if "Rate limit reached" in r.getMessage()]
    assert warnings == ["Rate limit reached on generic-report-ip from an IP"]
    assert "testclient" not in warnings[0] and "127.0.0.1" not in warnings[0]


def test_switch_off_answers_before_auth_and_body_checks(client, tmp_db, monkeypatch):
    monkeypatch.setattr(config, "GENERIC_PATCHING", False)
    r = client.post("/api/generic/reports", json={"bogus": 1})
    assert r.status_code == 404 and r.json()["detail"] == "Generic patching is off"


def test_ip_guard_runs_before_auth(client, tmp_db, monkeypatch):
    from utils import ratelimit
    monkeypatch.setattr(ratelimit.generic_report_ip_rate_limit, "limit", 1)
    assert client.post("/api/generic/reports", json=body()).status_code == 401
    assert client.post("/api/generic/reports", json=body()).status_code == 429


def _access_record(path):
    return logging.LogRecord("uvicorn.access", logging.INFO, __file__, 1,
                             '%s - "%s %s HTTP/%s" %d', ("1.2.3.4:5", "POST", path, "1.1", 201), None)


@pytest.mark.parametrize("path, kept", [
    ("/api/generic/reports", False),
    ("/v2/api/generic/reports", False),
    ("/v2/api/generic/reports?x=1", False),
    ("/api/generic", True),
    ("/api/generic/reports/extra", True),
    ("/api/health", True),
])
def test_access_log_filter_drops_only_the_report_post(path, kept):
    from utils.logging_config import SkipReportAccessLog
    assert SkipReportAccessLog().filter(_access_record(path)) is kept


def test_access_log_filter_is_installed_on_uvicorn_access():
    from utils.logging_config import SkipReportAccessLog
    assert any(isinstance(f, SkipReportAccessLog) for f in logging.getLogger("uvicorn.access").filters)
