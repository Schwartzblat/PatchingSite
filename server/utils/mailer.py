"""Outgoing mail: password reset links and bug reports.

Plain SMTP with TLS (Gmail with an app password by default; see config.SMTP_*).
Sending happens on a small executor, never on the request thread: an SMTP
session is a TLS handshake plus several round trips, and a slow or unreachable
server would otherwise hold a FastAPI worker for up to SMTP_TIMEOUT_SECONDS per
request - and make the response time of /forgot-password reveal whether any
account matched, which the endpoint otherwise hides.

Delivery failures are logged, never raised to the caller: the callers have
already answered the user (and bug reports are stored before they are mailed).
Credentials are never logged, including when an error message echoes them.
"""
import smtplib
import ssl
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from email.message import EmailMessage
from email.utils import formataddr, formatdate, make_msgid

import config
from utils.logging_config import logger

SMTP_TIMEOUT_SECONDS = 20
# Two, so one hung session cannot hold up every mail behind it, without letting a
# burst of requests open dozens of connections to one account's SMTP server
# (Gmail throttles and then locks accounts that do that).
MAIL_WORKERS = 2

_executor = None
_executor_lock = threading.Lock()


def is_configured() -> bool:
    """True when mail can actually be sent: a host, both credentials and a sender."""
    return config.smtp_configured()


def _get_executor() -> ThreadPoolExecutor:
    """Created on first use. Its threads are joined at interpreter exit, so a mail
    queued just before a restart is still delivered rather than dropped."""
    global _executor
    with _executor_lock:
        if _executor is None:
            _executor = ThreadPoolExecutor(max_workers=MAIL_WORKERS, thread_name_prefix="mail")
        return _executor


def build_message(to: str, subject: str, body: str, reply_to: str | None = None) -> EmailMessage:
    """A plain-text message.

    EmailMessage refuses a header value containing CR or LF (ValueError), which
    is what stops header injection through a subject or a Reply-To.
    """
    message = EmailMessage()
    message["From"] = formataddr((config.SITE_HOST, config.MAIL_FROM))
    message["To"] = to
    message["Subject"] = subject
    if reply_to:
        message["Reply-To"] = reply_to
    message["Date"] = formatdate(usegmt=True)
    # make_msgid() without a domain calls socket.getfqdn(), a DNS lookup that
    # can stall for seconds on a misconfigured host.
    message["Message-ID"] = make_msgid(domain=config.MAIL_FROM.rpartition("@")[2] or config.SITE_HOST)
    message.set_content(body)
    return message


def _open_session():
    context = ssl.create_default_context()
    if config.SMTP_PORT == 465:
        return smtplib.SMTP_SSL(config.SMTP_HOST, config.SMTP_PORT,
                                timeout=SMTP_TIMEOUT_SECONDS, context=context)
    session = smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT, timeout=SMTP_TIMEOUT_SECONDS)
    session.starttls(context=context)
    return session


def deliver(message: EmailMessage) -> None:
    """Send synchronously. Raises on any failure; `send` is the normal entry point."""
    with _open_session() as session:
        session.login(config.SMTP_USERNAME, config.SMTP_PASSWORD)
        session.send_message(message)


def _describe(error: BaseException) -> str:
    """The error for the log, with the credentials scrubbed out of it.

    Servers answer a failed login with their own text, and some libraries put
    the arguments they were given into exception messages; neither may carry
    the SMTP password into app.log.
    """
    text = f"{type(error).__name__}: {error}"
    for secret in (config.SMTP_PASSWORD,):
        if secret:
            text = text.replace(secret, "<redacted>")
    return text


def _deliver_logged(message: EmailMessage) -> bool:
    try:
        deliver(message)
    except Exception as error:  # smtplib, ssl and socket errors alike
        # Deliberately not logger.exception: a traceback's frames are not needed
        # to diagnose SMTP trouble, and _describe is the one place that scrubs.
        logger.error('Could not send mail %r to %s: %s', message["Subject"], message["To"], _describe(error))
        return False
    logger.info('Sent mail %r to %s', message["Subject"], message["To"])
    return True


def send(to: str, subject: str, body: str, reply_to: str | None = None) -> Future:
    """Queue a plain-text mail and return at once.

    The Future resolves to True when the server accepted the message and False
    when it was not sent (unconfigured, or failed - already logged). Building the
    message happens here, on the caller's thread, so a malformed header is a
    ValueError the caller sees rather than a silent failure in the background.
    """
    message = build_message(to, subject, body, reply_to)
    if not is_configured():
        logger.warning('SMTP is not configured; not sending mail %r to %s', subject, to)
        future = Future()
        future.set_result(False)
        return future
    return _get_executor().submit(_deliver_logged, message)
