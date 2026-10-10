"""manage.py doctor and delete-user."""
import argparse
import sqlite3
from datetime import datetime, timedelta, timezone

import pytest

import config
import database

SMTP_ON = {"SMTP_USERNAME": "site@example.com", "SMTP_PASSWORD": "smtp-secret-value", "MAIL_FROM": "site@example.com"}
SMTP_OFF = {"SMTP_USERNAME": "", "SMTP_PASSWORD": "", "MAIL_FROM": ""}


def doctor(monkeypatch, capsys, **settings):
    """Run doctor as production with these config overrides; (exit code, output)."""
    import manage

    monkeypatch.setattr(config, "IS_PRODUCTION", True)
    monkeypatch.setattr(config, "ENVIRONMENT", "production")
    for name, value in settings.items():
        monkeypatch.setattr(config, name, value)
    code = manage.cmd_doctor(None)
    return code, capsys.readouterr().out


def test_missing_smtp_in_production_warns_but_never_fails(monkeypatch, capsys):
    """doctor is the unit's ExecStartPre: a non-zero exit keeps the site down."""
    code_on, _ = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_ON)
    code_off, out = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_OFF)

    assert code_off == code_on
    [line] = [line for line in out.splitlines() if "SMTP" in line and "warn" in line]
    assert "not configured" in line


def test_the_smtp_warning_says_new_accounts_cannot_subscribe(monkeypatch, capsys):
    """Without mail no new account can verify its address, and checkout needs one."""
    code_on, _ = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_ON)
    code_off, out = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_OFF)

    assert code_off == code_on
    assert "verify" in out and "subscribe" in out


def test_configured_smtp_is_reported_without_its_password(monkeypatch, capsys):
    _, out = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_ON)
    assert "site@example.com" in out
    assert SMTP_ON["SMTP_PASSWORD"] not in out


def test_a_derived_app_key_warns_but_never_fails(monkeypatch, capsys):
    """Rotating JWT_SECRET_KEY would silently re-derive it and break every APK out there."""
    code_explicit, _ = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=True, **SMTP_ON)
    code_derived, out = doctor(monkeypatch, capsys, APP_JWT_SECRET_KEY_EXPLICIT=False, **SMTP_ON)

    assert code_derived == code_explicit
    assert any("APP_JWT_SECRET_KEY" in line and "warn" in line for line in out.splitlines())
    assert config.APP_JWT_SECRET_KEY not in out
    assert config.JWT_SECRET_KEY not in out


@pytest.mark.parametrize("cutoff, expected", [
    (None, "accepted"),
    (datetime.now(timezone.utc) + timedelta(days=30), "until"),
    (datetime.now(timezone.utc) - timedelta(days=1), "refused"),
])
def test_doctor_reports_the_legacy_token_cutoff(monkeypatch, capsys, cutoff, expected):
    _, out = doctor(monkeypatch, capsys, LEGACY_APP_TOKEN_CUTOFF=cutoff, **SMTP_ON)
    [line] = [line for line in out.splitlines() if "legacy app tokens" in line]
    assert expected in line


def test_doctor_says_the_admin_panel_is_off_without_admins(monkeypatch, capsys):
    _, out = doctor(monkeypatch, capsys, ADMIN_USERNAMES=frozenset(), **SMTP_ON)
    [line] = [line for line in out.splitlines() if "admin panel" in line]
    assert "off" in line and "warn" not in line


def test_doctor_names_the_admins(tmp_db, monkeypatch, capsys):
    _register("owner")
    code_off, _ = doctor(monkeypatch, capsys, ADMIN_USERNAMES=frozenset(), **SMTP_ON)
    code, out = doctor(monkeypatch, capsys, ADMIN_USERNAMES=frozenset({"owner"}), **SMTP_ON)

    assert code == code_off
    [line] = [line for line in out.splitlines() if "admin panel" in line]
    assert "owner" in line and "warn" not in line


def test_doctor_warns_but_never_fails_about_a_listed_name_with_no_account(tmp_db, monkeypatch, capsys):
    """Not a hole (/register refuses listed names), only a panel nobody can open yet."""
    code_off, _ = doctor(monkeypatch, capsys, ADMIN_USERNAMES=frozenset(), **SMTP_ON)
    code, out = doctor(monkeypatch, capsys, ADMIN_USERNAMES=frozenset({"ghost"}), **SMTP_ON)

    assert code == code_off
    assert any("ghost" in line and "warn" in line for line in out.splitlines())


# --- delete-user ------------------------------------------------------------------

def _register(username, email=None, customer_id=None):
    assert database.register_user(database.RegisterRequest(
        username=username, email=email or f"{username}@example.com", password="correct horse battery"))
    if customer_id:
        assert database.update_user_customer_id("username", username, customer_id)


def _report(username, email="reply@example.com"):
    return database.add_bug_report(source="web", message="Something broke on the page", email=email,
                                   username=username, client_ip="198.51.100.4", context={},
                                   created_at=datetime.now(timezone.utc))


def _report_row(db_path, report_id):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    row = dict(conn.execute("SELECT * FROM bug_reports WHERE id = ?", (report_id,)).fetchone())
    conn.close()
    return row


def delete_user(username, yes=True):
    import manage

    return manage.cmd_delete_user(argparse.Namespace(username=username, yes=yes))


def test_delete_user_removes_the_row_and_unlinks_its_reports(tmp_db, capsys):
    _register("alice", customer_id="cus_alice")
    _register("bob")
    alices = _report("alice")
    bobs = _report("bob")

    assert delete_user("ALICE") == 0

    assert database.get_user("alice") is None
    assert database.get_user("bob") is not None
    unlinked = _report_row(tmp_db, alices)
    assert (unlinked["username"], unlinked["email"], unlinked["client_ip"]) == (None, None, None)
    assert unlinked["message"], "the report itself is kept"
    assert _report_row(tmp_db, bobs)["username"] == "bob"

    out = capsys.readouterr().out
    assert "cus_alice" in out, "the owner still has to cancel the subscription in Stripe"


def test_delete_user_says_when_there_is_no_stripe_customer(tmp_db, capsys):
    _register("free")
    assert delete_user("free") == 0
    assert "no Stripe customer" in capsys.readouterr().out


def test_delete_user_asks_first_and_a_no_keeps_the_account(tmp_db, monkeypatch):
    _register("alice")
    monkeypatch.setattr("builtins.input", lambda prompt="": "n")

    assert delete_user("alice", yes=False) == 1
    assert database.get_user("alice") is not None


def test_delete_user_goes_ahead_on_a_yes(tmp_db, monkeypatch):
    _register("alice")
    monkeypatch.setattr("builtins.input", lambda prompt="": "y")

    assert delete_user("alice", yes=False) == 0
    assert database.get_user("alice") is None


def test_delete_user_treats_a_closed_stdin_as_no(tmp_db, monkeypatch):
    _register("alice")

    def _eof(prompt=""):
        raise EOFError

    monkeypatch.setattr("builtins.input", _eof)
    assert delete_user("alice", yes=False) == 1
    assert database.get_user("alice") is not None


def test_delete_user_rejects_an_unknown_username(tmp_db, capsys):
    assert delete_user("ghost") == 1
    assert "no user" in capsys.readouterr().out.lower()
