import logging
import logging.handlers
import re

from config import LOG_LEVEL, LOG_PATH

_REDACT_KEYS = ("token", "password", "secret", "authorization", "api_key", "apikey")
_JWT_RE = re.compile(r"\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")


class RedactFilter(logging.Filter):
    """Last-resort guard against credentials reaching the log file.

    This is a backstop, not a licence to log secrets: call sites must not pass
    them in the first place. It rewrites JWT-shaped substrings anywhere in the
    message (including behind a `Bearer ` prefix, which an earlier version
    missed because it tested the whole string rather than searching it) and
    blanks mapping values stored under an obviously sensitive key.
    """

    @staticmethod
    def _scrub(value):
        if isinstance(value, str):
            return _JWT_RE.sub(lambda m: f"<jwt:{m.group(0)[-6:]}>", value)
        return value

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple):
            record.args = tuple(self._scrub(arg) for arg in record.args)
        elif isinstance(record.args, dict):
            record.args = {
                key: ("<redacted>" if any(k in str(key).lower() for k in _REDACT_KEYS)
                      else self._scrub(value))
                for key, value in record.args.items()
            }
        record.msg = self._scrub(record.msg)
        return True


def configure_logging() -> logging.Logger:
    root = logging.getLogger()
    if getattr(root, "_patchingsite_configured", False):
        return logging.getLogger("patchingsite")

    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    formatter = logging.Formatter(
        "%(asctime)s - %(levelname)s - %(name)s - %(message)s"
    )

    # delay=True so merely importing the app does not create the file. Without
    # it, `python -c "import main"` writes into the production data dir.
    file_handler = logging.handlers.RotatingFileHandler(
        LOG_PATH, maxBytes=10 * 1024 * 1024, backupCount=5, encoding="utf-8", delay=True
    )
    file_handler.setFormatter(formatter)

    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)

    redact = RedactFilter()
    for handler in (file_handler, stream_handler):
        handler.addFilter(redact)
        root.addHandler(handler)

    root.setLevel(LOG_LEVEL)
    # The stripe library logs a line per API request at INFO, request path and
    # all, which put Stripe customer ids into the journal and app.log on every
    # subscription check. Its warnings and errors still come through.
    logging.getLogger("stripe").setLevel(logging.WARNING)
    root._patchingsite_configured = True
    return logging.getLogger("patchingsite")


logger = configure_logging()
