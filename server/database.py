import json
import sqlite3
from datetime import date, datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Annotated, Optional

from pydantic import BaseModel, EmailStr, StringConstraints

from config import DB_PATH, MAX_DEVICES_ALLOWED
from utils.jwt_utils import UserInDB
from utils.logging_config import logger
from utils.passwords import (
    MAX_PASSWORD_LENGTH,
    MIN_PASSWORD_LENGTH,
    SCHEME_ARGON2,
    hash_password,
    needs_rehash,
    spend_a_verify,
    verify_password,
)

SQLITE_TIMEOUT_SECONDS = 15
# How the columns this code writes store a moment: UTC, ISO 8601, second
# precision - the same text the API hands to clients.
TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"

Username = Annotated[str, StringConstraints(
    strip_whitespace=True, min_length=3, max_length=32, pattern=r"^[A-Za-z0-9_.-]+$"
)]
Password = Annotated[str, StringConstraints(
    min_length=MIN_PASSWORD_LENGTH, max_length=MAX_PASSWORD_LENGTH
)]

# Usernames are stored as typed but are unique case-insensitively, so every
# lookup and the unique index all go through lower().
_SELECT_BY_USERNAME = "SELECT * FROM users WHERE lower(username) = lower(?)"

# Only these identifiers may be used to locate a row by customer id. The full
# statement is a literal per key so no caller input ever reaches the SQL text.
_CUSTOMER_ID_UPDATES = {
    "username": "UPDATE users SET customer_id = ? WHERE lower(username) = lower(?)",
    # No "email" key on purpose: addresses are never verified at registration
    # and this column is not unique, so keying a customer write on email let one
    # account claim another's Stripe subscription.
    "id": "UPDATE users SET customer_id = ? WHERE id = ?",
}

# False when the live database already contained duplicate usernames and the
# unique index could not be created; register_user then has to pre-check.
_username_index_enforced = False

# Same story for email: False means historical duplicates blocked the unique
# index, so register_user falls back to checking before it inserts.
_email_index_enforced = False


class DeviceResult(str, Enum):
    """Outcome of registering a device fingerprint. Truthy when the device may be used."""
    ADDED = "added"
    ALREADY_REGISTERED = "already_registered"
    LIMIT_REACHED = "limit_reached"
    USER_NOT_FOUND = "user_not_found"
    ERROR = "error"

    def __bool__(self) -> bool:
        return self in (DeviceResult.ADDED, DeviceResult.ALREADY_REGISTERED)


class DeviceResetResult(str, Enum):
    RESET = "reset"
    REFUSED = "refused"  # inside the interval since the previous reset
    USER_NOT_FOUND = "user_not_found"


class EmailChangeResult(str, Enum):
    CHANGED = "changed"
    TAKEN = "taken"  # another account holds the address, in any case
    USER_NOT_FOUND = "user_not_found"


class RegisterResult(str, Enum):
    """Why a registration was rejected. Truthy only when the account was created.

    Compare with truthiness, never `is False` - the members are enum values, so
    an identity check against False silently reads every rejection as success.
    """
    CREATED = "created"
    # One value for any uniqueness collision, on purpose. Recording whether it
    # was the username or the email that was taken let /register leak which
    # accounts exist (account enumeration), so that distinction stops here.
    TAKEN = "taken"

    def __bool__(self) -> bool:
        return self is RegisterResult.CREATED


class RegisterRequest(BaseModel):
    username: Username
    email: EmailStr
    password: Password


class LoginRequest(BaseModel):
    # A username or the account's email address (see _account_to_sign_in), under
    # the old field name so neither client had to change. 254 is the longest
    # address SMTP allows; usernames stop at 32.
    username: Annotated[str, StringConstraints(strip_whitespace=True, max_length=254)]
    password: Annotated[str, StringConstraints(max_length=1024)]


def _connect() -> sqlite3.Connection:
    # isolation_level=None keeps sqlite3 out of the way so the one place that
    # needs a transaction can drive BEGIN IMMEDIATE itself.
    conn = sqlite3.connect(DB_PATH, timeout=SQLITE_TIMEOUT_SECONDS, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute(f"PRAGMA busy_timeout={SQLITE_TIMEOUT_SECONDS * 1000}")
    return conn


def _load_fingerprints(raw: Optional[str]) -> list:
    try:
        fingerprints = json.loads(raw or '[]')
    except ValueError:
        logger.error('Corrupt device_fingerprints column, treating as empty')
        return []
    return fingerprints if isinstance(fingerprints, list) else []


def row_to_user(row: sqlite3.Row) -> UserInDB:
    stripped_row = dict(row)
    fingerprints = stripped_row.pop("device_fingerprints", '[]')
    # Parsed here rather than by pydantic so one bad value degrades to "never
    # reset" instead of making the whole account unreadable. The same goes for
    # the verification stamp, where a bad value reads as "not verified": the
    # user can simply verify again.
    reset_at = _parse_utc(stripped_row.pop("devices_reset_at", None))
    verified_at = _parse_utc(stripped_row.pop("email_verified_at", None))
    return UserInDB(**stripped_row, device_fingerprints=_load_fingerprints(fingerprints),
                    devices_reset_at=reset_at, email_verified_at=verified_at)


def _ensure_unique_index(conn: sqlite3.Connection, create_sql: str, duplicates_sql: str, description: str) -> bool:
    """Create a unique index unless the live data already violates it."""
    duplicates = [row[0] for row in conn.execute(duplicates_sql).fetchall()]
    if duplicates:
        logger.error(
            'Not creating unique index on %s: the database already contains duplicates: %s. '
            'Merge or rename them and restart to enforce uniqueness.',
            description, ', '.join(repr(value) for value in duplicates[:20]),
        )
        return False
    conn.execute(create_sql)
    return True


def _add_email_verified_at(conn: sqlite3.Connection) -> None:
    """Add users.email_verified_at, marking every account that exists now as verified.

    Grandfathering is keyed on the column being ADDED, never on its value: the
    accounts on the live table when this ships signed up before verification
    existed and must keep subscribing and resetting passwords as before, while
    anyone who signs up afterwards starts at NULL. A step that instead stamped
    "every row still NULL" on each start would verify every later account the
    next time the service restarted.

    Both statements share one transaction. A column that outlived a failed
    stamp would make the next start skip this step, and every existing customer
    would be locked out of checkout and password reset for good. The column
    check is repeated inside the transaction so that a `manage.py migrate`
    racing the service's own start finds the column already added, rather than
    failing on a duplicate ALTER and taking the start down with it.
    """
    conn.execute("BEGIN IMMEDIATE")
    try:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(users)").fetchall()}
        if "email_verified_at" in columns:
            return
        now = datetime.now(timezone.utc).strftime(TIMESTAMP_FORMAT)
        conn.execute("ALTER TABLE users ADD COLUMN email_verified_at TEXT DEFAULT NULL")
        cursor = conn.execute("UPDATE users SET email_verified_at = ?", (now,))
        conn.execute("COMMIT")
        logger.info('Migrating users: added email_verified_at, %s existing account(s) marked verified',
                    cursor.rowcount)
    finally:
        if conn.in_transaction:
            conn.execute("ROLLBACK")


def init():
    global _username_index_enforced
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    conn = _connect()
    try:
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS users
                     (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         username TEXT NOT NULL, email TEXT NOT NULL,
                         password_hash TEXT NOT NULL, password_salt TEXT NOT NULL,
                         password_scheme TEXT NOT NULL DEFAULT 'legacy',
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         customer_id TEXT DEFAULT NULL,
                         device_fingerprints TEXT DEFAULT "[]",
                         devices_reset_at TEXT DEFAULT NULL,
                         email_verified_at TEXT DEFAULT NULL
                     )
                     """)

        # Runs against the populated production database, so every step below is
        # idempotent and driven by what the table actually looks like today.
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(users)").fetchall()}
        if "password_scheme" not in columns:
            logger.info('Migrating users: adding password_scheme, existing rows marked legacy')
            conn.execute("ALTER TABLE users ADD COLUMN password_scheme TEXT NOT NULL DEFAULT 'legacy'")
        if "devices_reset_at" not in columns:
            # NULL means "never reset", so every existing user may reset at once.
            logger.info('Migrating users: adding devices_reset_at')
            conn.execute("ALTER TABLE users ADD COLUMN devices_reset_at TEXT DEFAULT NULL")
        if "email_verified_at" not in columns:
            _add_email_verified_at(conn)

        _username_index_enforced = _ensure_unique_index(
            conn,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_unique ON users (lower(username))",
            "SELECT lower(username) FROM users GROUP BY lower(username) HAVING COUNT(*) > 1",
            "users.username",
        )
        _ensure_unique_index(
            conn,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_customer_id_unique "
            "ON users (customer_id) WHERE customer_id IS NOT NULL AND customer_id != ''",
            "SELECT customer_id FROM users WHERE customer_id IS NOT NULL AND customer_id != '' "
            "GROUP BY customer_id HAVING COUNT(*) > 1",
            "users.customer_id",
        )
        # Always present so lookups are indexed even when the unique variant
        # below has to be skipped.
        conn.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users (email)")
        # Unique on a case-folded address, attempted rather than assumed: the
        # live table predates any such constraint. If it already holds
        # duplicates this logs them and carries on unenforced, so a deploy can
        # never be taken down by historical data. Run `python manage.py check`
        # before deploying to see whether it will apply.
        _email_index_enforced = _ensure_unique_index(
            conn,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique ON users (lower(email))",
            "SELECT lower(email) FROM users WHERE email IS NOT NULL AND email != '' "
            "GROUP BY lower(email) HAVING COUNT(*) > 1",
            "users.email",
        )
        globals()['_email_index_enforced'] = _email_index_enforced

        # Reports from the site's and the store app's "report a bug" forms. Not
        # tied to users by a foreign key: most are anonymous, and deleting an
        # account (manage.py delete-user) unlinks its reports rather than
        # losing them. mailed_at stays NULL until SUPPORT_EMAIL accepted the
        # mail, so `manage.py bug-reports` shows which ones never arrived.
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS bug_reports
                     (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         created_at TEXT NOT NULL,
                         source TEXT NOT NULL,
                         message TEXT NOT NULL,
                         email TEXT DEFAULT NULL,
                         username TEXT DEFAULT NULL,
                         client_ip TEXT DEFAULT NULL,
                         context TEXT NOT NULL DEFAULT '{}',
                         mailed_at TEXT DEFAULT NULL
                     )
                     """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_bug_reports_username ON bug_reports (lower(username))")

        # On-device patching. `body` is the record JSON without revision, so
        # "identical" means byte-identical body. seq is one counter across
        # records and bundles, allocated inside the writer's transaction.
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS patch_records
                     (
                         id INTEGER PRIMARY KEY,
                         package TEXT NOT NULL,
                         version_code INTEGER NOT NULL,
                         version_name TEXT NOT NULL,
                         apk_sha256 TEXT NOT NULL,
                         dex_sha256 TEXT NOT NULL,
                         patcher_version TEXT NOT NULL,
                         source TEXT NOT NULL,
                         body TEXT NOT NULL,
                         seq INTEGER NOT NULL,
                         updated_at TEXT NOT NULL,
                         UNIQUE (package, dex_sha256, patcher_version)
                     )
                     """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_patch_records_apk ON patch_records (package, apk_sha256)")
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS patch_bundles
                     (
                         package TEXT NOT NULL,
                         patcher_version TEXT NOT NULL,
                         min_store_version_code INTEGER NOT NULL,
                         min_sdk INTEGER NOT NULL,
                         sha256 TEXT NOT NULL,
                         size INTEGER NOT NULL,
                         seq INTEGER NOT NULL,
                         uploaded_at TEXT NOT NULL,
                         PRIMARY KEY (package, patcher_version)
                     )
                     """)
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS signing_keys
                     (
                         user_id INTEGER PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
                         nonce BLOB NOT NULL,
                         ciphertext BLOB NOT NULL,
                         cert_sha256 TEXT NOT NULL,
                         cert_sha1 TEXT NOT NULL,
                         created_at TEXT NOT NULL
                     )
                     """)
        # Customer reports on the generic ad remover. Deliberately no account,
        # username, customer id or IP column, and the date is only the day, so a
        # row cannot be matched with an access log by time.
        conn.execute("""
                     CREATE TABLE IF NOT EXISTS generic_reports
                     (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         created_on TEXT NOT NULL,
                         package TEXT NOT NULL,
                         app_name TEXT NOT NULL,
                         version_name TEXT NOT NULL,
                         version_code INTEGER NOT NULL,
                         worked INTEGER NOT NULL,
                         note TEXT DEFAULT NULL,
                         store_version_code INTEGER NOT NULL,
                         android_sdk INTEGER NOT NULL
                     )
                     """)
    finally:
        conn.close()


def _upgrade_password(user: UserInDB, password: str) -> None:
    """Re-hash a legacy password on successful login. Never fails the login.

    Conditioned on the hash the login verified against, like set_password: a
    password reset that lands between that verify and this write must win, or
    the upgrade would quietly restore the password the user just replaced.
    """
    password_hash, scheme = hash_password(password)
    conn = _connect()
    try:
        cursor = conn.execute(
            "UPDATE users SET password_hash = ?, password_salt = '', password_scheme = ? "
            "WHERE lower(username) = lower(?) AND password_hash = ?",
            (password_hash, scheme, user.username, user.password_hash),
        )
        if cursor.rowcount == 0:
            logger.info('Skipped the hash upgrade for %s: the password changed meanwhile', user.username)
            return
        user.password_hash = password_hash
        user.password_salt = ''
        user.password_scheme = scheme
        logger.info('Upgraded password hash to %s for user %s', scheme, user.username)
    except sqlite3.Error:
        logger.exception('Could not upgrade password hash for user %s', user.username)
    finally:
        conn.close()


def _account_to_sign_in(identifier: str) -> Optional[UserInDB]:
    """The account a sign-in names: its username, or the email address it registered with.

    The username is tried first. Registration forbids "@" in usernames, so the two
    cannot collide for new accounts, but a row older than that pattern may hold
    one, and it must keep reaching its own account by name.

    An address counts only when exactly one account holds it. users.email is
    unique wherever init could build the index (it is live), but where historical
    duplicates blocked it, picking one of several accounts would sign the person
    into whichever the guess landed on. Those users sign in with their username.
    """
    user = get_user(identifier)
    if user is not None or "@" not in identifier:
        return user
    accounts = get_users_by_email(identifier)
    if len(accounts) > 1:
        logger.warning('Refused a sign-in by email: %s accounts share that address', len(accounts))
        return None
    return accounts[0] if accounts else None


def login_user(request: LoginRequest) -> Optional[UserInDB]:
    logger.debug('Logging in with user: %s', request.username)
    user = _account_to_sign_in(request.username)
    if user is None:
        # Same cost as a wrong password on a real account; see spend_a_verify.
        spend_a_verify(request.password)
        return None
    if user.password_scheme != SCHEME_ARGON2:
        # The legacy scheme is ~1 ms of SHA-512, which would single out the
        # accounts that exist but have not logged in since the Argon2 migration.
        spend_a_verify(request.password)
    if not verify_password(request.password, user.password_hash, user.password_salt, user.password_scheme):
        return None
    if needs_rehash(user.password_hash, user.password_scheme):
        _upgrade_password(user, request.password)
    return user


def register_user(request: RegisterRequest) -> RegisterResult:
    logger.debug('Register user: %s', request.username)
    if not _username_index_enforced and get_user(request.username) is not None:
        # Degraded path: the unique index could not be created, so uniqueness has
        # to be checked here even though it races.
        return RegisterResult.TAKEN
    password_hash, scheme = hash_password(request.password)
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO users (username, email, password_hash, password_salt, password_scheme) "
            "VALUES (?, ?, ?, ?, ?)",
            (request.username, request.email, password_hash, '', scheme),
        )
        return RegisterResult.CREATED
    except sqlite3.IntegrityError:
        # sqlite names the offending index in the error, but we deliberately do
        # not read it: every uniqueness collision returns the same result so the
        # caller can never tell a taken username from a taken email.
        logger.info('Rejected registration for username %s: uniqueness collision', request.username)
        return RegisterResult.TAKEN
    finally:
        conn.close()


def update_user_customer_id(identifier: str, identifier_value: str, customer_id: str) -> bool:
    logger.debug('Updating customer id: %s for %s', customer_id, identifier_value)
    statement = _CUSTOMER_ID_UPDATES.get(identifier)
    if statement is None:
        raise ValueError(f"Unsupported identifier column: {identifier!r}")
    conn = _connect()
    try:
        cursor = conn.execute(statement, (customer_id, identifier_value))
        return cursor.rowcount > 0
    except sqlite3.IntegrityError:
        logger.error('customer_id %s is already attached to another user', customer_id)
        return False
    finally:
        conn.close()


def update_user_device_fingerprints(username: str, device_fingerprint: str) -> DeviceResult:
    """Register a device against the account. Read and write share one transaction
    so two devices cannot both slip past MAX_DEVICES_ALLOWED."""
    logger.debug('Registering device fingerprint for %s', username)
    if not device_fingerprint:
        return DeviceResult.ERROR
    conn = _connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute(
                "SELECT id, device_fingerprints FROM users WHERE lower(username) = lower(?)",
                (username,),
            ).fetchone()
            if row is None:
                logger.error('Cannot register device, no such user: %s', username)
                return DeviceResult.USER_NOT_FOUND

            fingerprints = _load_fingerprints(row["device_fingerprints"])
            if device_fingerprint in fingerprints:
                return DeviceResult.ALREADY_REGISTERED
            if len(fingerprints) >= MAX_DEVICES_ALLOWED:
                logger.warning('Device limit of %s reached for user %s', MAX_DEVICES_ALLOWED, username)
                return DeviceResult.LIMIT_REACHED

            fingerprints.append(device_fingerprint)
            conn.execute(
                "UPDATE users SET device_fingerprints = ? WHERE id = ?",
                (json.dumps(fingerprints), row["id"]),
            )
            conn.execute("COMMIT")
            return DeviceResult.ADDED
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    except sqlite3.Error:
        logger.exception('Failed to register device for user %s', username)
        return DeviceResult.ERROR
    finally:
        conn.close()


def _parse_utc(raw: Optional[str]) -> Optional[datetime]:
    if not raw:
        return None
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        logger.error('Unreadable timestamp %r in the database, treating it as absent', raw)
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def reset_devices(username: str, now: datetime, interval: timedelta):
    """Clear every device slot, at most once per `interval`.

    Returns (DeviceResetResult, last_reset_at). On REFUSED, last_reset_at is the
    reset that is still blocking this one. Read and write share one transaction,
    so two concurrent resets cannot both pass the interval check.
    """
    conn = _connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute(
                "SELECT id, devices_reset_at FROM users WHERE lower(username) = lower(?)", (username,)
            ).fetchone()
            if row is None:
                return DeviceResetResult.USER_NOT_FOUND, None
            last = _parse_utc(row["devices_reset_at"])
            if last is not None and now < last + interval:
                return DeviceResetResult.REFUSED, last
            conn.execute(
                "UPDATE users SET device_fingerprints = '[]', devices_reset_at = ? WHERE id = ?",
                (now.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT), row["id"]),
            )
            conn.execute("COMMIT")
            logger.info('Cleared the device slots of user %s', username)
            return DeviceResetResult.RESET, now
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    finally:
        conn.close()


def get_user(username: str) -> Optional[UserInDB]:
    if not username:
        return None
    conn = _connect()
    try:
        row = conn.execute(_SELECT_BY_USERNAME, (username,)).fetchone()
    finally:
        conn.close()
    return row_to_user(row) if row else None


def get_users_by_email(email: str) -> list:
    """Every account registered with this address, oldest first.

    Plural on purpose: users.email is unique only where historical duplicates did
    not block the index (see init), so one address can own several accounts.
    """
    if not email:
        return []
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM users WHERE lower(email) = lower(?) ORDER BY id", (email,)
        ).fetchall()
    finally:
        conn.close()
    return [row_to_user(row) for row in rows]


def delete_user(username: str) -> Optional[UserInDB]:
    """Delete an account, as the privacy policy promises. The deleted account, or None.

    The account's bug reports are unlinked in the same transaction: username,
    reply address and IP are cleared, and the text is kept so a known bug
    stays known. Stripe is the caller's business: cancelling a paid subscription
    is not something a delete should do as a side effect. Shared by manage.py
    delete-user and the admin panel.
    """
    conn = _connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute(_SELECT_BY_USERNAME, (username,)).fetchone()
            if row is None:
                return None
            # manage.py may run against a database the service has not started
            # on since bug_reports was added.
            if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='bug_reports'").fetchone():
                conn.execute(
                    "UPDATE bug_reports SET username = NULL, email = NULL, client_ip = NULL "
                    "WHERE lower(username) = lower(?)",
                    (row["username"],),
                )
            conn.execute("DELETE FROM users WHERE id = ?", (row["id"],))
            conn.execute("COMMIT")
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    finally:
        conn.close()
    logger.info('Deleted user %s', row["username"])
    return row_to_user(row)


def list_users(q: str, limit: int) -> tuple[list, int]:
    """(at most `limit` accounts, newest first; how many match in all), for the admin panel.

    `q`, when not empty, keeps the accounts whose username or email contains it,
    in any case, as a literal substring: LIKE's own wildcards are escaped, so a
    search for "bo_" does not also find "bob".
    """
    where, params = "", ()
    if q:
        escaped = q.lower().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        pattern = f"%{escaped}%"
        where = " WHERE lower(username) LIKE ? ESCAPE '\\' OR lower(email) LIKE ? ESCAPE '\\'"
        params = (pattern, pattern)
    conn = _connect()
    try:
        total = conn.execute("SELECT COUNT(*) FROM users" + where, params).fetchone()[0]
        rows = conn.execute("SELECT * FROM users" + where + " ORDER BY id DESC LIMIT ?",
                            (*params, limit)).fetchall()
    finally:
        conn.close()
    return [row_to_user(row) for row in rows], total


def set_email(username: str, email: str, verified_at: Optional[datetime]) -> EmailChangeResult:
    """Give an account a new address, verified at `verified_at` or unverified (None).

    For the admin panel. Read and write share one transaction, and another
    account holding the address (in any case) is checked inside it, because
    the unique index on email is attempted, not assumed: where historical
    duplicates blocked it (see init), this check is all there is. The
    account's own address may change case. Verification links already mailed
    die on their own, since mark_email_verified matches the address they were
    minted for.
    """
    stamp = verified_at.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT) if verified_at else None
    conn = _connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute("SELECT id FROM users WHERE lower(username) = lower(?)", (username,)).fetchone()
            if row is None:
                return EmailChangeResult.USER_NOT_FOUND
            if conn.execute("SELECT 1 FROM users WHERE lower(email) = lower(?) AND id != ?",
                            (email, row["id"])).fetchone():
                return EmailChangeResult.TAKEN
            conn.execute("UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                         (email, stamp, row["id"]))
            conn.execute("COMMIT")
            return EmailChangeResult.CHANGED
        except sqlite3.IntegrityError:
            return EmailChangeResult.TAKEN
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    finally:
        conn.close()


def clear_devices(username: str) -> bool:
    """Free every device slot, for the admin panel. False when there is no such account.

    Unlike reset_devices this leaves devices_reset_at alone: support clearing
    the slots must not spend the user's own reset or start its cooldown.
    """
    conn = _connect()
    try:
        cursor = conn.execute("UPDATE users SET device_fingerprints = '[]' WHERE lower(username) = lower(?)",
                              (username,))
        return cursor.rowcount > 0
    finally:
        conn.close()


def set_password(user: UserInDB, new_password: str, email_verified_at: Optional[datetime] = None) -> bool:
    """Replace an account's password, provided it has not changed since `user` was read.

    The WHERE on the old hash makes this a compare-and-swap: two requests racing
    with the same single-use reset link cannot both win, because the first
    write changes the hash the second one is conditioned on. The new password is
    hashed exactly as registration hashes one (Argon2id).

    `email_verified_at` is for the reset-by-email path, where using the link
    proves the inbox: when given, an unverified account is stamped in the same
    write, and an earlier stamp is kept. Opt-in rather than always, so a future
    change-password-while-signed-in cannot verify an address by accident.
    """
    password_hash, scheme = hash_password(new_password)
    stamp = email_verified_at.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT) if email_verified_at else None
    conn = _connect()
    try:
        cursor = conn.execute(
            "UPDATE users SET password_hash = ?, password_salt = '', password_scheme = ?, "
            "email_verified_at = COALESCE(email_verified_at, ?) "
            "WHERE lower(username) = lower(?) AND password_hash = ?",
            (password_hash, scheme, stamp, user.username, user.password_hash),
        )
        return cursor.rowcount > 0
    finally:
        conn.close()


def mark_email_verified(username: str, email: str, verified_at: datetime) -> bool:
    """Record that `email` is proven to belong to this account.

    False when no account has this username AND this address: the link was
    minted for an address the account no longer uses, so it proves nothing
    about the current one. Both are matched in the one UPDATE, so an address
    change cannot slip in between a check and the write.

    Idempotent, and an earlier stamp is kept: verifying twice is not an error,
    and the first moment is the one that is true.
    """
    conn = _connect()
    try:
        cursor = conn.execute(
            "UPDATE users SET email_verified_at = COALESCE(email_verified_at, ?) "
            "WHERE lower(username) = lower(?) AND lower(email) = lower(?)",
            (verified_at.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT), username, email),
        )
        return cursor.rowcount > 0
    finally:
        conn.close()


def add_bug_report(source: str, message: str, email: Optional[str], username: Optional[str],
                   client_ip: Optional[str], context: dict, created_at: datetime) -> int:
    """Store a report and return its id."""
    conn = _connect()
    try:
        cursor = conn.execute(
            "INSERT INTO bug_reports (created_at, source, message, email, username, client_ip, context) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (created_at.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT), source, message, email,
             username, client_ip, json.dumps(context or {}, ensure_ascii=False)),
        )
        return cursor.lastrowid
    finally:
        conn.close()


def mark_bug_report_mailed(report_id: int, mailed_at: datetime) -> None:
    conn = _connect()
    try:
        conn.execute("UPDATE bug_reports SET mailed_at = ? WHERE id = ?",
                     (mailed_at.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT), report_id))
    finally:
        conn.close()


def add_generic_report(package: str, app_name: str, version_name: str, version_code: int, worked: bool,
                       note: Optional[str], store_version_code: int, android_sdk: int,
                       created_on: date) -> int:
    """Store a customer report and return its id. Nothing here identifies the reporter."""
    conn = _connect()
    try:
        cursor = conn.execute(
            "INSERT INTO generic_reports (created_on, package, app_name, version_name, version_code, "
            "worked, note, store_version_code, android_sdk) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (created_on.isoformat(), package, app_name, version_name, version_code, 1 if worked else 0,
             note, store_version_code, android_sdk),
        )
        return cursor.lastrowid
    finally:
        conn.close()


def list_generic_reports(limit: int) -> tuple[list[dict], int]:
    """The newest `limit` reports and the total count."""
    conn = _connect()
    try:
        rows = conn.execute("SELECT * FROM generic_reports ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM generic_reports").fetchone()[0]
        return [dict(row) for row in rows], total
    finally:
        conn.close()


def get_user_by_customer_id(customer_id: str) -> Optional[UserInDB]:
    if not customer_id:
        return None
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE customer_id = ?", (customer_id,)).fetchone()
    finally:
        conn.close()
    return row_to_user(row) if row else None
