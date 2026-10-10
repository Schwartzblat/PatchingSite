"""POST /api/bug-reports and `manage.py bug-reports`. Mail is always a recorder."""
import json
import sqlite3
from concurrent.futures import Future

import pytest
from starlette.testclient import TestClient

import config
import database
from utils import mailer
from utils.ratelimit import bug_report_rate_limit

PASSWORD = "correct horse battery"
MESSAGE = "The download button does nothing on my phone."


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def outbox(monkeypatch):
    """Mail configured and accepted; each send recorded."""
    sent = []

    def _send(to, subject, body, reply_to=None):
        sent.append({"to": to, "subject": subject, "body": body, "reply_to": reply_to})
        future = Future()
        future.set_result(True)
        return future

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "send", _send)
    return sent


def report(client, **overrides):
    body = {"message": MESSAGE, "source": "web"}
    body.update(overrides)
    return client.post("/api/bug-reports", json=body)


def rows(db_path):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    result = [dict(row) for row in conn.execute("SELECT * FROM bug_reports ORDER BY id")]
    conn.close()
    return result


def sign_in(client, username="alice", email=None):
    client.post("/api/register", json={
        "username": username, "email": email or f"{username}@example.com", "password": PASSWORD})
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200


def test_an_anonymous_report_is_stored_and_mailed(client, outbox, tmp_db):
    context = {"url": "https://androidmod.site/apps/whatsapp", "user_agent": "Firefox"}

    response = report(client, email="reporter@example.com", context=context)

    assert response.status_code == 201
    body = response.json()
    assert body == {"id": body["id"], "message": "Thanks! Your report was sent."}
    [row] = rows(tmp_db)
    assert row["id"] == body["id"]
    assert row["message"] == MESSAGE
    assert row["source"] == "web"
    assert row["email"] == "reporter@example.com"
    assert row["username"] is None
    assert row["client_ip"]
    assert json.loads(row["context"]) == context
    assert row["mailed_at"] is not None

    [mail] = outbox
    assert mail["to"] == config.SUPPORT_EMAIL
    assert mail["reply_to"] == "reporter@example.com"
    assert MESSAGE in mail["body"]
    assert "Firefox" in mail["body"]


def test_a_signed_in_report_replies_to_the_account_email(client, outbox, tmp_db):
    sign_in(client)

    assert report(client, source="web").status_code == 201

    assert rows(tmp_db)[0]["username"] == "alice"
    # outbox[0] is the signup's verification link; the report is the last mail.
    assert outbox[-1]["reply_to"] == "alice@example.com"
    assert "alice" in outbox[-1]["subject"]


def test_a_given_email_beats_the_account_email(client, outbox):
    sign_in(client)
    report(client, email="other@example.com")
    assert outbox[-1]["reply_to"] == "other@example.com"


def test_a_bearer_token_identifies_the_reporter(client, outbox, tmp_db):
    sign_in(client)
    token = client.post("/api/auth/token", json={"username": "alice", "password": PASSWORD}).json()["access_token"]
    client.cookies.clear()

    response = client.post("/api/bug-reports", json={"message": MESSAGE, "source": "android"},
                           headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 201
    assert rows(tmp_db)[0]["username"] == "alice"


def test_a_bad_token_is_treated_as_anonymous_not_refused(client, outbox, tmp_db):
    """A stale cookie must never stop someone telling us the site is broken."""
    response = client.post("/api/bug-reports", json={"message": MESSAGE, "source": "android"},
                           headers={"Authorization": "Bearer not-a-jwt"})

    assert response.status_code == 201
    assert rows(tmp_db)[0]["username"] is None


def test_a_blank_email_counts_as_none(client, outbox, tmp_db):
    assert report(client, email="").status_code == 201
    assert rows(tmp_db)[0]["email"] is None
    assert outbox[0]["reply_to"] is None


@pytest.mark.parametrize("overrides", [
    {"message": "too short"},
    {"message": "x" * 5001},
    {"message": " " * 20},
    {"source": "ios"},
    {"source": None},
    {"email": "not-an-email"},
    {"email": "a" * 250 + "@example.com"},
    {"context": {f"k{i}": "v" for i in range(11)}},
    {"context": {"app_version": 42}},
    {"context": {"url": "x" * 501}},
    {"context": "a string"},
])
def test_invalid_reports_are_422(client, outbox, overrides, tmp_db):
    body = {"message": MESSAGE, "source": "web"}
    body.update(overrides)
    body = {key: value for key, value in body.items() if value is not None}

    assert client.post("/api/bug-reports", json=body).status_code == 422
    assert rows(tmp_db) == []


def test_a_report_is_stored_even_without_smtp(client, tmp_db, monkeypatch):
    monkeypatch.setattr(mailer, "is_configured", lambda: False)

    response = report(client)

    assert response.status_code == 201
    [row] = rows(tmp_db)
    assert row["mailed_at"] is None


def test_a_report_is_stored_even_when_sending_fails(client, tmp_db, monkeypatch):
    def _send(to, subject, body, reply_to=None):
        future = Future()
        future.set_result(False)  # what mailer.send resolves to after a logged failure
        return future

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "send", _send)

    assert report(client).status_code == 201
    assert rows(tmp_db)[0]["mailed_at"] is None


def test_past_the_daily_mail_budget_reports_are_stored_but_not_mailed(client, outbox, tmp_db, monkeypatch):
    """Every report is a login to the site's one Gmail account. A botnet past the
    per-IP limit must not burn its daily quota - that would silently switch off
    password reset too."""
    from utils.ratelimit import bug_report_mail_budget

    monkeypatch.setattr(bug_report_mail_budget, "limit", 1)

    assert report(client).status_code == 201
    assert report(client).status_code == 201

    assert len(outbox) == 1
    assert [row["mailed_at"] is not None for row in rows(tmp_db)] == [True, False]


def test_bug_reports_are_rate_limited_per_ip(client, outbox):
    for _ in range(bug_report_rate_limit.limit):
        assert report(client).status_code == 201
    response = report(client)
    assert response.status_code == 429
    assert len(outbox) == bug_report_rate_limit.limit


# --- schema and manage.py ----------------------------------------------------------

def test_init_creates_the_table_idempotently(tmp_db):
    database.init()
    tables = {row[0] for row in sqlite3.connect(tmp_db).execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'")}
    assert "bug_reports" in tables


def test_check_reports_a_missing_table_without_creating_it(monkeypatch, tmp_path, capsys):
    import manage

    db_path = tmp_path / "old.db"
    conn = sqlite3.connect(db_path)
    conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT, email TEXT, "
                 "customer_id TEXT, password_scheme TEXT)")
    conn.close()
    monkeypatch.setattr(config, "DB_PATH", db_path)

    manage.cmd_check(None)

    assert "bug_reports" in capsys.readouterr().out
    tables = {row[0] for row in sqlite3.connect(db_path).execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'")}
    assert "bug_reports" not in tables


def test_manage_lists_recent_reports_newest_first(client, outbox, capsys):
    import argparse

    import manage

    for index in range(3):
        report(client, message=f"Report number {index} with enough text")

    assert manage.cmd_bug_reports(argparse.Namespace(limit=2)) == 0

    out = capsys.readouterr().out
    assert "Report number 2" in out and "Report number 1" in out
    assert "Report number 0" not in out
    assert out.index("Report number 2") < out.index("Report number 1")


def test_manage_escapes_terminal_control_sequences(client, outbox, capsys):
    """Reports are anonymous input; an ANSI/OSC sequence in one must not be able
    to clear, recolour or retitle the operator's terminal, or hide other lines."""
    import argparse

    import manage

    report(client, message="Looks fine\x1b[2J\x1b]0;pwned\x07 until here\rOVERWRITE",
           context={"os": "Android\x1b[31m 15\nFAKE: line"})

    manage.cmd_bug_reports(argparse.Namespace(limit=5))

    out = capsys.readouterr().out
    assert "\x1b" not in out and "\x07" not in out and "\r" not in out
    assert "\\x1b[2J" in out
    assert "FAKE: line" not in out.splitlines(), "a context value must not forge its own line"


def test_manage_bug_reports_copes_with_no_table(monkeypatch, tmp_path, capsys):
    import argparse

    import manage

    db_path = tmp_path / "empty.db"
    sqlite3.connect(db_path).close()
    monkeypatch.setattr(config, "DB_PATH", db_path)

    assert manage.cmd_bug_reports(argparse.Namespace(limit=20)) == 0
    assert "no bug reports" in capsys.readouterr().out.lower()
