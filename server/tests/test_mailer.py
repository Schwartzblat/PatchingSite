"""Outgoing mail. smtplib is always a fake - no test opens a socket."""
import logging
import smtplib
import threading

import pytest

import config
from utils import mailer

PASSWORD = "app-password-do-not-log"


class FakeSMTP:
    """Records what a real SMTP session would have been asked to do."""

    instances = []
    fail_with = None

    def __init__(self, host, port, timeout=None, context=None):
        self.host, self.port, self.timeout, self.context = host, port, timeout, context
        self.logged_in = None
        self.sent = []
        self.started_tls = False
        self.thread = threading.current_thread().name
        FakeSMTP.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def starttls(self, context=None):
        self.started_tls = True

    def login(self, username, password):
        if FakeSMTP.fail_with is not None:
            raise FakeSMTP.fail_with
        self.logged_in = (username, password)

    def send_message(self, message):
        self.sent.append(message)


@pytest.fixture
def smtp(monkeypatch):
    FakeSMTP.instances = []
    FakeSMTP.fail_with = None
    monkeypatch.setattr(smtplib, "SMTP_SSL", FakeSMTP)
    monkeypatch.setattr(smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(config, "SMTP_HOST", "smtp.example.com")
    monkeypatch.setattr(config, "SMTP_PORT", 465)
    monkeypatch.setattr(config, "SMTP_USERNAME", "sender@example.com")
    monkeypatch.setattr(config, "SMTP_PASSWORD", PASSWORD)
    monkeypatch.setattr(config, "MAIL_FROM", "sender@example.com")
    return FakeSMTP


def test_unconfigured_without_credentials(monkeypatch):
    monkeypatch.setattr(config, "SMTP_USERNAME", "")
    monkeypatch.setattr(config, "SMTP_PASSWORD", "")
    assert not mailer.is_configured()


def test_configured_with_credentials(smtp):
    assert mailer.is_configured()


def test_mail_is_sent_over_implicit_tls_off_the_request_thread(smtp):
    ok = mailer.send("user@example.com", "Hello", "Body text", reply_to="help@example.com").result(timeout=10)

    assert ok is True
    [session] = smtp.instances
    assert (session.host, session.port) == ("smtp.example.com", 465)
    assert session.context is not None, "implicit TLS must verify certificates"
    assert session.logged_in == ("sender@example.com", PASSWORD)
    assert session.thread != threading.current_thread().name

    [message] = session.sent
    assert message["To"] == "user@example.com"
    assert message["Subject"] == "Hello"
    assert message["Reply-To"] == "help@example.com"
    assert "sender@example.com" in message["From"]
    assert message["Message-ID"]
    assert message.get_content().strip() == "Body text"


def test_any_other_port_upgrades_with_starttls(smtp, monkeypatch):
    """Never plaintext: 465 is implicit TLS, anything else must STARTTLS."""
    monkeypatch.setattr(config, "SMTP_PORT", 587)

    assert mailer.send("user@example.com", "Hello", "Body").result(timeout=10) is True
    assert smtp.instances[0].started_tls


def test_nothing_is_sent_when_unconfigured(smtp, monkeypatch):
    monkeypatch.setattr(config, "SMTP_PASSWORD", "")

    assert mailer.send("user@example.com", "Hello", "Body").result(timeout=10) is False
    assert smtp.instances == []


def test_a_failure_is_logged_without_the_password(smtp, caplog):
    smtp.fail_with = smtplib.SMTPAuthenticationError(535, b"5.7.8 Username and Password not accepted")
    caplog.set_level(logging.DEBUG)

    assert mailer.send("user@example.com", "Hello", "Body").result(timeout=10) is False

    errors = [record for record in caplog.records if record.levelno >= logging.ERROR]
    assert errors, "a failed send must be logged"
    assert all(PASSWORD not in record.getMessage() for record in caplog.records)


def test_a_password_echoed_in_an_error_is_scrubbed(smtp, caplog):
    """Belt and braces: whatever a server or library puts in the error text."""
    smtp.fail_with = OSError(f"login failed for sender@example.com:{PASSWORD}")

    assert mailer.send("user@example.com", "Hello", "Body").result(timeout=10) is False
    assert all(PASSWORD not in record.getMessage() for record in caplog.records)
    assert any("OSError" in record.getMessage() for record in caplog.records)


def test_header_injection_is_refused(smtp):
    with pytest.raises(ValueError):
        mailer.send("user@example.com", "Hello\nBcc: everyone@example.com", "Body")
