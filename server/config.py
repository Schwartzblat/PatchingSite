import base64
import binascii
import dataclasses
import hashlib
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from dotenv import load_dotenv

SERVER_DIR = Path(__file__).resolve().parent
REPO_ROOT = SERVER_DIR.parent

# Local overrides, so a development machine can be pointed at Stripe's test mode
# without editing the file that holds the live credentials.
#
# The order looks backwards and is not: python-dotenv never replaces a value that
# is already set, so whichever file loads FIRST wins. Loading .dev_env first is
# what makes it an override of .env, and the real process environment still beats
# both, because it was set before either load ran:
#
#     process environment  >  .dev_env  >  .env
#
# Both files are gitignored. .dev_env is absent in production; if one ever turns
# up there, the warning at the foot of this module says so.
DEV_ENV_PATH = REPO_ROOT / ".dev_env"
DEV_ENV_LOADED = DEV_ENV_PATH.is_file()
if DEV_ENV_LOADED:
    load_dotenv(DEV_ENV_PATH)
load_dotenv(REPO_ROOT / ".env")


class ConfigError(RuntimeError):
    """Raised at import time when required configuration is missing."""


def _int_env(name: str, default: int) -> int:
    """An optional integer setting.

    Blank counts as unset: `SMTP_PORT=` left in .env used to raise a bare
    ValueError at import, which fails `manage.py doctor` (the unit's
    ExecStartPre) and keeps the whole site down over an empty line. Anything
    else that is not an integer is a ConfigError naming the variable.
    """
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw.strip())
    except ValueError:
        raise ConfigError(f"{name} must be a whole number, not {raw!r}.")


def _bool_env(name: str, default: bool = False) -> bool:
    """An optional on/off switch: 1/true/yes/on or 0/false/no/off in any case.

    Blank counts as unset. Anything else is a ConfigError naming the variable,
    so a typo cannot leave a feature in a state the owner did not choose.
    """
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    value = raw.strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{name} must be one of 1/true/yes/on or 0/false/no/off, not {raw!r}.")


def _parse_cutoff(name: str, raw: str | None) -> datetime | None:
    """An optional ISO 8601 date or datetime, as an aware UTC datetime.

    A bare date means midnight UTC at its start. Malformed is a ConfigError, not
    a silent None: a mistyped cutoff would otherwise leave the owner believing
    something had been switched off when it had not.
    """
    if raw is None or not raw.strip():
        return None
    try:
        parsed = datetime.fromisoformat(raw.strip())
    except ValueError:
        raise ConfigError(f"{name} must be an ISO date such as 2027-01-31, not {raw!r}.")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _require(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise ConfigError(
            f"{name} is not set. Add it to {SERVER_DIR.parent / '.env'} or the process environment."
        )
    return value


@dataclasses.dataclass(frozen=True)
class AppConfig:
    name: str
    package: str
    # `key` is the website's route segment (/apps/<key>) and the Android client's
    # stable id. `features` lived only in client/src/apps.js, whose own comment
    # already called this file the source of truth; a third client made that true.
    key: str
    features: tuple = ()


APPS = (
    AppConfig(
        name="WhatsApp", package="com.whatsapp", key="whatsapp",
        # The available rows of the WhatsAppPatcher README's feature table, in
        # its order and its wording, minus the ones nobody subscribes for:
        # "Signature Verifier Bypass" is what makes a re-signed build run at all,
        # and "Enable all AB tests" is a developer screen. "Restore a backup
        # without reinstalling" is part of the Google Drive backup row.
        features=(
            "Keep Revoked Messages",
            "Trash Icon on Revoked Messages",
            "Unlimited Time to Delete for Everyone",
            "Disable Read Receipts Per Chat",
            "Meta AI Removed From the Home Screen",
            "Save View Once Media",
            "Profile Picture Screenshot",
            "Unlock Premium Features",
            "Tag a Group With @everyone",
            "Settings Screen to Toggle Features",
            "Group and Member Statistics",
            "Send More Group History to New Members",
            "Shared Members Between Groups",
            "Back Up Chats to Your Own Google Drive",
            "Unlimited Pinned Chats",
        ),
    ),
    AppConfig(
        name="Moovit", package="com.tranzmate", key="moovit",
        features=("No Ads", "Live Location Enabled"),
    ),
    AppConfig(
        name="12+", package="com.keshet.mako.VOD", key="mako",
        features=("No Ads",),
    ),
    AppConfig(
        name="Reshet 13+", package="com.applicaster.iReshet", key="reshet",
        # The ReshetPatcher README's feature list, regrouped by what a viewer
        # sees: the player's video ads, then every other ad in the app.
        features=("No Video Ads", "No Full-Screen or Banner Ads"),
    ),
    AppConfig(
        name="OkCupid", package="com.okcupid.okcupid", key="okcupid",
        # A deliberate subset of the OkCupidPremiumPatcher README's feature
        # list: the patched build also unblurs and names the gated "Interested
        # in You" cards, which the catalogue does not advertise.
        features=(
            "Unlimited Rewinds",
            "See who viewed/liked you (full profile)",
            "Regular Like on OkCupid Picks",
        ),
    ),
)
APPS_BY_PACKAGE = {app.package: app for app in APPS}

# Defaults to production so the failure mode is safe: this flag decides whether
# session cookies get `Secure` and whether X-Forwarded-For is trusted, and an
# unset variable on the live host must not silently downgrade either. Local
# development sets ENVIRONMENT=development explicitly.
ENVIRONMENT = os.getenv("ENVIRONMENT", "production")
IS_PRODUCTION = ENVIRONMENT == "production"

PATCHED_APPS_PATH = Path(os.getenv("PATCHED_APPS_PATH", "/opt/patched"))
# The store app itself (android/), served as-is: it is built already signed with
# the shared keystore, so unlike the patched apps it is never re-signed per user.
# A plain file at the root of PATCHED_APPS_PATH cannot collide with a package
# directory, and list_versions only ever looks inside those.
STORE_APK_PATH = Path(os.getenv("STORE_APK_PATH", PATCHED_APPS_PATH / "store.apk"))
DATA_DIR = Path(os.getenv("SERVER_DATA_DIR", "/opt/server_data"))
# Settable on its own so a second server beside the live one (v2,
# deploy/patchingsite-v2.service) shares the accounts database while keeping its
# own cache, logs and bundles under its own SERVER_DATA_DIR.
DB_PATH = Path(os.getenv("DB_PATH") or DATA_DIR / "users.db")
DOWNLOAD_CACHE_DIR = DATA_DIR / "download_cache"
LOG_PATH = Path(os.getenv("LOG_PATH", DATA_DIR / "app.log"))
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO" if IS_PRODUCTION else "DEBUG")

# Legacy password scheme (1000 rounds of salted SHA-512). Kept only so existing
# users can still log in; every successful login upgrades them to Argon2.
LEGACY_NUMBER_OF_HASHES = 1000

JWT_SECRET_KEY = _require("JWT_SECRET_KEY")
# App tokens are embedded in every APK we hand out, so they must NOT be signed
# with the key that mints browser session cookies. Derived rather than a second
# env var so deployments keep working without config changes.
APP_JWT_SECRET_KEY = os.getenv("APP_JWT_SECRET_KEY") or hashlib.blake2b(
    JWT_SECRET_KEY.encode(), person=b"apptoken", digest_size=32
).hexdigest()
# `manage.py doctor` warns when the key is derived: rotating JWT_SECRET_KEY
# then silently changes it too, and every APK already handed out stops working.
APP_JWT_SECRET_KEY_EXPLICIT = bool(os.getenv("APP_JWT_SECRET_KEY"))

# APKs built before the split-key scheme carry a token signed with the session
# key, with no `typ` and no expiry, and they are still accepted so those phones
# keep working (jwt_utils._legacy_app_token_data). Such a token can never
# expire on its own, so this date is the only way to retire them. Unset keeps
# accepting them; once it has passed they get a 401 and the app must be
# downloaded again. Picking the date is a product decision, hence no default.
LEGACY_APP_TOKEN_CUTOFF = _parse_cutoff("LEGACY_APP_TOKEN_CUTOFF", os.getenv("LEGACY_APP_TOKEN_CUTOFF"))

STRIPE_API_KEY = _require("STRIPE_API_KEY")
STRIPE_PRODUCT_ID = _require("STRIPE_PRODUCT_ID")
# Which Stripe account half the keys point at. Stripe encodes the mode in the key
# itself, so this needs no separate flag that could disagree with reality.
STRIPE_TEST_MODE = STRIPE_API_KEY.startswith(("sk_test_", "rk_test_", "pk_test_"))


def stripe_dashboard_url(customer_id: str) -> str:
    """Where the owner manages a customer: the test half of the dashboard on test keys.

    Reads STRIPE_TEST_MODE at call time, so tests can monkeypatch it.
    """
    return "https://dashboard.stripe.com" + ("/test" if STRIPE_TEST_MODE else "") + f"/customers/{customer_id}"

FRONTEND_URL = os.getenv("FRONTEND_URL", "http://localhost:5173")
# How the site names itself in emails and user-facing messages ("go to
# androidmod.site/subscription"): the public host, without the scheme.
SITE_HOST = urlparse(FRONTEND_URL).netloc or "androidmod.site"

# Outgoing mail (password resets, bug reports), over SMTP with TLS. Defaults suit
# a Gmail account with an app password; only the two credentials are required,
# and without them the mail-dependent features answer 503 / skip the email
# rather than fail at import - the site must keep running without mail.
SMTP_HOST = os.getenv("SMTP_HOST", "smtp.gmail.com")
# 465 is implicit TLS. Any other port is upgraded with STARTTLS; never plaintext.
SMTP_PORT = _int_env("SMTP_PORT", 465)
SMTP_USERNAME = os.getenv("SMTP_USERNAME", "")
SMTP_PASSWORD = os.getenv("SMTP_PASSWORD", "")
# Gmail rewrites From to the authenticated account unless the address is a
# verified alias of it, so the account itself is the only safe default.
MAIL_FROM = os.getenv("MAIL_FROM") or SMTP_USERNAME
# Where bug reports go, and the address users are told to write to when
# self-service fails. Shown to users, so it has to be a real inbox.
SUPPORT_EMAIL = os.getenv("SUPPORT_EMAIL") or "sudorm.ext@gmail.com"


def smtp_configured() -> bool:
    """True when mail can actually be sent: a host, both credentials and a sender.

    Lives here rather than in utils.mailer so `manage.py doctor` can ask without
    importing the app's logging setup. Reads the module globals at call time,
    so tests can monkeypatch them.
    """
    return bool(SMTP_HOST and SMTP_USERNAME and SMTP_PASSWORD and MAIL_FROM)


def _admin_usernames(raw: str | None) -> frozenset:
    """ADMIN_USERNAMES as lowercased names. Spaces and empty entries are ignored."""
    return frozenset(name.strip().lower() for name in (raw or "").split(",") if name.strip())


# Accounts that may use the admin panel (/admin, api/admin.py), by username,
# compared case-insensitively like every other username. Empty, the default,
# turns the panel off. Configuration rather than a column on purpose: nothing
# the panel or the API writes can make anyone an admin, so a bug in either
# cannot escalate an account. /register refuses these names, because a name
# listed before its account exists would go to whoever registered it first:
# register the account, then list it.
ADMIN_USERNAMES = _admin_usernames(os.getenv("ADMIN_USERNAMES"))


def is_admin(username: str | None) -> bool:
    """Whether `username` is listed. Reads the global at call time, so tests can monkeypatch it.

    Compares the way the database does: no stripping and ASCII-only folding,
    because every lookup is sqlite's `lower(username) = lower(?)`. Python's
    strip() and Unicode lower() are looser, so "owner " (a trailing space) or
    a Kelvin-sign lookalike would count as the admin here while being a
    different account to the database, and could sign in and act as the owner.
    """
    return bool(username) and username.isascii() and username.lower() in ADMIN_USERNAMES


def _cors_origins(is_production: bool, configured: str | None) -> tuple:
    """Origins allowed to make credentialed browser requests.

    An explicit CORS_ORIGINS always wins. The localhost dev origin is only ever a
    default off production - baking it into the production allowlist would leave a
    dev origin trusted with credentials for no reason. Never falls back to "*",
    which allow_credentials=True forbids anyway.
    """
    if configured is not None:
        raw = configured
    else:
        origins = ["https://www.androidmod.site", "https://androidmod.site"]
        if not is_production:
            origins.insert(0, "http://localhost:5173")
        raw = ",".join(origins)
    return tuple(origin.strip() for origin in raw.split(",") if origin.strip())


CORS_ORIGINS = _cors_origins(IS_PRODUCTION, os.getenv("CORS_ORIGINS"))

SESSION_TOKEN_TTL_MINUTES = _int_env("SESSION_TOKEN_TTL_MINUTES", 180)
APP_TOKEN_TTL_DAYS = _int_env("APP_TOKEN_TTL_DAYS", 400)
# Mobile clients hold a bearer token instead of a session cookie. It is
# deliberately long-lived and, unlike the cookie, cannot be revoked server-side.
MOBILE_TOKEN_TTL_DAYS = _int_env("MOBILE_TOKEN_TTL_DAYS", 30)
# The absolute ceiling on a renewal chain. /auth/renew slides the 30-day expiry,
# so without this a stolen token could be renewed monthly forever - an unbounded
# risk, where the one signed off in the design doc was bounded. Measured from the
# `iat` of the FIRST token in the chain, which every renewal carries forward.
MOBILE_TOKEN_MAX_LIFETIME_DAYS = _int_env("MOBILE_TOKEN_MAX_LIFETIME_DAYS", 90)
MAX_DEVICES_ALLOWED = _int_env("MAX_DEVICES_ALLOWED", 3)
# How often a user may clear their device slots themselves. Rare enough that an
# account cannot be passed around a group one reset at a time, frequent enough
# that a new phone does not need an email to support.
DEVICE_RESET_INTERVAL_DAYS = _int_env("DEVICE_RESET_INTERVAL_DAYS", 30)

# Download jobs
DOWNLOAD_WORKERS = _int_env("DOWNLOAD_WORKERS", 2)
# Queued + running jobs one account may have at once. The whole queue is only
# DOWNLOAD_WORKERS * 4 deep, so without this one subscriber tapping through
# every version could lock everyone else out of downloading.
DOWNLOAD_MAX_JOBS_PER_USER = _int_env("DOWNLOAD_MAX_JOBS_PER_USER", 2)
DOWNLOAD_JOB_TTL_SECONDS = _int_env("DOWNLOAD_JOB_TTL_SECONDS", 60 * 60)
DOWNLOAD_URL_TTL_SECONDS = _int_env("DOWNLOAD_URL_TTL_SECONDS", 10 * 60)
DOWNLOAD_CACHE_MAX_BYTES = _int_env("DOWNLOAD_CACHE_MAX_BYTES", 20 * 1024**3)
SIGNING_TIMEOUT_SECONDS = _int_env("SIGNING_TIMEOUT_SECONDS", 20 * 60)


def _master_key(raw: str | None) -> bytes | None:
    """SIGNING_KEYS_MASTER_KEY as raw bytes: base64 of exactly 32 bytes, or None if unset."""
    if raw is None or not raw.strip():
        return None
    try:
        key = base64.b64decode(raw.strip(), validate=True)
    except (binascii.Error, ValueError):
        raise ConfigError("SIGNING_KEYS_MASTER_KEY must be base64 of exactly 32 bytes.")
    if len(key) != 32:
        raise ConfigError("SIGNING_KEYS_MASTER_KEY must be base64 of exactly 32 bytes.")
    return key


def _package_set(raw: str | None) -> frozenset:
    return frozenset(name.strip() for name in (raw or "").split(",") if name.strip())


# On-device patching. None of these is required at import: an endpoint that needs
# an unset one answers 503 "Not configured". The secrets are never logged.
CRAWLER_SECRET = os.getenv("CRAWLER_SECRET") or None
BUNDLE_UPLOAD_SECRET = os.getenv("BUNDLE_UPLOAD_SECRET") or None
SIGNING_KEYS_MASTER_KEY = _master_key(os.getenv("SIGNING_KEYS_MASTER_KEY"))
BUNDLE_PUBLIC_KEY_PATH = Path(os.getenv("BUNDLE_PUBLIC_KEY_PATH") or DATA_DIR / "bundle-signing.pub.pem")
BUNDLES_DIR = Path(os.getenv("BUNDLES_DIR") or DATA_DIR / "bundles")
# Packages switched to on-device patching. Empty by default: turning one on is a
# launch-day operations step, so deploying changes nothing for current subscribers.
ON_DEVICE_PACKAGES = _package_set(os.getenv("ON_DEVICE_PACKAGES"))
# The generic ad remover: a bundle (package `generic`) the store app applies to any
# installed app. Off by default; the three generic reads answer 404 until it is on.
# Uploading a generic bundle is allowed either way, so it can be staged first.
GENERIC_PACKAGE = "generic"
GENERIC_PATCHING = _bool_env("GENERIC_PATCHING", False)
# Hand-edited list of apps tested with it; ships empty (see utils/works_with.py).
GENERIC_WORKS_WITH_PATH = Path(os.getenv("GENERIC_WORKS_WITH_PATH") or Path(__file__).parent / "generic_works_with.json")
OFFICIAL_SOURCES = {"com.whatsapp": "whatsapp.com"}
OFFICIAL_APK_URLS = {"com.whatsapp": "https://www.whatsapp.com/android/current/WhatsApp.apk"}


if not IS_PRODUCTION and FRONTEND_URL.startswith("https://"):
    import warnings

    warnings.warn(
        f"ENVIRONMENT={ENVIRONMENT!r} but FRONTEND_URL is {FRONTEND_URL!r}. "
        "Session cookies will be issued without the Secure flag over HTTPS.",
        stacklevel=2,
    )

if IS_PRODUCTION and DEV_ENV_LOADED:
    import warnings

    warnings.warn(
        f"{DEV_ENV_PATH} exists on a production host and has overridden .env. "
        "That file is meant for development only - delete it.",
        stacklevel=2,
    )

if IS_PRODUCTION and STRIPE_TEST_MODE:
    import warnings

    warnings.warn(
        "ENVIRONMENT=production but STRIPE_API_KEY is a test-mode key. "
        "No real payment can succeed with it.",
        stacklevel=2,
    )
