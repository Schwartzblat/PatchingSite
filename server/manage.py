#!/usr/bin/env python
"""Operational tooling for the PatchingSite backend.

    python manage.py check                    # read-only: will the migration apply cleanly?
    python manage.py migrate                  # apply it (idempotent)
    python manage.py doctor                   # validate configuration for production
    python manage.py bug-reports [--limit N]  # read-only: recent bug reports, newest first
    python manage.py delete-user NAME [--yes] # delete an account on request (asks first)
    python manage.py grant-trial NAME (--until YYYY-MM-DD | --days N) [--yes]
                                              # a free trial on Stripe, linked to the account

`check` and `bug-reports` open the database read-only and never write, so they
are safe to point at the live file at any time.
"""
import argparse
import json
import os
import re
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

import config

OK, WARN, BAD = "  ok  ", " warn ", " FAIL "


def _ro(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


# _duplicates interpolates `column` straight into SQL. Every caller passes a name
# from cmd_check's hardcoded tuple, so this is a guard, not a live fix: it stops
# the pattern being copied into a request path where the name is attacker-controlled.
_DUP_COLUMNS = frozenset({"username", "email", "customer_id"})


def _duplicates(conn, column: str):
    if column not in _DUP_COLUMNS:
        raise ValueError(f"refusing to interpolate unknown column into SQL: {column!r}")
    return [
        (row[0], row[1])
        for row in conn.execute(
            f"SELECT lower({column}), COUNT(*) FROM users "
            f"WHERE {column} IS NOT NULL AND {column} != '' "
            f"GROUP BY lower({column}) HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC"
        ).fetchall()
    ]


def cmd_check(_args) -> int:
    db_path = Path(config.DB_PATH)
    print(f"database: {db_path}")
    if not db_path.exists():
        print(f"[{OK}] no database yet; init() will create it with every constraint")
        return 0

    problems = 0
    conn = _ro(db_path)
    try:
        columns = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
        indexes = {r["name"] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        total = conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        print(f"users: {total}")

        if "password_scheme" in columns:
            rows = conn.execute(
                "SELECT password_scheme, COUNT(*) FROM users GROUP BY password_scheme").fetchall()
            spread = ", ".join(f"{r[0]}={r[1]}" for r in rows) or "none"
            print(f"[{OK}] password_scheme present ({spread})")
        else:
            print(f"[{OK}] password_scheme missing; migrate adds it, existing rows -> 'legacy'")

        if "devices_reset_at" in columns:
            resets = conn.execute(
                "SELECT COUNT(*) FROM users WHERE devices_reset_at IS NOT NULL").fetchone()[0]
            print(f"[{OK}] devices_reset_at present ({resets} user(s) have reset their devices)")
        else:
            print(f"[{OK}] devices_reset_at missing; migrate adds it (NULL: every user may reset once)")

        if "email_verified_at" in columns:
            verified = conn.execute(
                "SELECT COUNT(*) FROM users WHERE email_verified_at IS NOT NULL").fetchone()[0]
            print(f"[{OK}] email_verified_at present ({verified} verified, {total - verified} unverified)")
        else:
            # Grandfathering happens only in the step that adds the column, so
            # this is the one moment the count of accounts it will cover is known.
            print(f"[{OK}] email_verified_at missing; migrate adds it and marks all {total} existing")
            print(f"         account(s) verified (they signed up before verification existed)")

        for column, index, label in (
            ("username", "idx_users_username_unique", "usernames"),
            ("email", "idx_users_email_unique", "email addresses"),
            ("customer_id", "idx_users_customer_id_unique", "Stripe customer ids"),
        ):
            if column not in columns:
                continue
            dupes = _duplicates(conn, column)
            if not dupes:
                state = "already enforced" if index in indexes else "will be created"
                print(f"[{OK}] {label}: no duplicates, unique index {state}")
            else:
                problems += 1
                shown = ", ".join(f"{value!r} x{count}" for value, count in dupes[:10])
                print(f"[{BAD}] {label}: {len(dupes)} duplicated value(s) -> {shown}")
                print(f"         the unique index will be SKIPPED; the deploy still succeeds,")
                print(f"         but uniqueness stays unenforced until these are resolved.")

        tables = {r["name"] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "bug_reports" in tables:
            reports = conn.execute("SELECT COUNT(*) FROM bug_reports").fetchone()[0]
            print(f"[{OK}] bug_reports present ({reports} report(s))")
        else:
            print(f"[{OK}] bug_reports missing; migrate creates it")

        for table in ("patch_records", "patch_bundles", "signing_keys"):
            if table in tables:
                rows = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                print(f"[{OK}] {table} present ({rows} row(s))")
            else:
                print(f"[{OK}] {table} missing; migrate creates it")

        wal = conn.execute("PRAGMA journal_mode").fetchone()[0]
        if wal != "wal":
            print(f"[{WARN}] journal_mode={wal}; migrate switches it to WAL. Back up")
            print(f"         users.db, users.db-wal and users.db-shm together afterwards.")
    finally:
        conn.close()

    print()
    if problems:
        print(f"{problems} blocking duplicate group(s). Migration is still SAFE to run -")
        print("it skips only the affected index and logs the offending values.")
        return 1
    print("clean: every constraint will apply.")
    return 0


def cmd_migrate(args) -> int:
    if args.dry_run:
        return cmd_check(args)
    import database

    print(f"applying migration to {config.DB_PATH}")
    database.init()
    print("done. re-checking:\n")
    return cmd_check(args)


def cmd_bug_reports(args) -> int:
    """Print the most recent bug reports, newest first.

    The live host has no sqlite3 CLI, and a report whose mail never arrived
    (SMTP down or unconfigured) exists nowhere else. Read-only.
    """
    db_path = Path(config.DB_PATH)
    if not db_path.exists():
        print(f"no bug reports: {db_path} does not exist")
        return 0
    conn = _ro(db_path)
    try:
        has_table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='bug_reports'").fetchone()
        if not has_table:
            print("no bug reports: the bug_reports table does not exist yet (run migrate)")
            return 0
        reports = conn.execute(
            "SELECT * FROM bug_reports ORDER BY id DESC LIMIT ?", (max(1, args.limit),)).fetchall()
    finally:
        conn.close()

    if not reports:
        print("no bug reports yet")
        return 0
    for report in reports:
        mailed = f"mailed {report['mailed_at']}" if report["mailed_at"] else "NOT MAILED"
        print(f"#{report['id']}  {report['created_at']}  {_printable(report['source'])}  {mailed}")
        print(f"  from: {_printable(report['username'] or 'anonymous')}"
              f"  email: {_printable(report['email'] or '-')}  ip: {_printable(report['client_ip'] or '-')}")
        try:
            context = json.loads(report["context"] or "{}")
        except ValueError:
            context = {"(unreadable)": report["context"]}
        for key, value in context.items():
            print(f"  {_printable(key)}: {_printable(value)}")
        for line in report["message"].splitlines() or [""]:
            print(f"  | {_printable(line)}")
        print()
    return 0


# C0/C1 controls (newline and tab included: callers split lines themselves)
# and the Unicode bidi overrides that can make text display in another order.
_UNPRINTABLE = re.compile(r"[\x00-\x1f\x7f-\x9f‪-‮⁦-⁩]")


def _printable(value) -> str:
    """Report text made safe for the operator's terminal.

    Bug reports are anonymous input. Printed raw, an ANSI or OSC escape could
    clear the screen, recolour or retitle it, or a CR/LF could forge a line that
    looks like part of another report. Escaped, they show up as `\\x1b` and are
    obviously the reporter's.
    """
    def _escape(match) -> str:
        code = ord(match.group())
        return f"\\x{code:02x}" if code < 0x100 else f"\\u{code:04x}"

    return _UNPRINTABLE.sub(_escape, str(value))


def cmd_delete_user(args) -> int:
    """Delete an account on request, as the privacy policy promises.

    database.delete_user does the work (shared with the admin panel): it
    removes the users row and unlinks the account's bug reports. Stripe is
    deliberately left to the owner: cancelling a paid subscription is not
    something a CLI typo should be able to do, so the customer id is printed
    for them to act on in the dashboard.
    """
    db_path = Path(config.DB_PATH)
    if not db_path.exists():
        print(f"no user {args.username!r}: {db_path} does not exist")
        return 1

    conn = _ro(db_path)
    try:
        user = conn.execute(
            "SELECT id, username, email, customer_id, created_at FROM users WHERE lower(username) = lower(?)",
            (args.username,),
        ).fetchone()
        if user is None:
            print(f"no user named {args.username!r}")
            return 1
        has_reports = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='bug_reports'").fetchone() is not None
        reports = conn.execute(
            "SELECT COUNT(*) FROM bug_reports WHERE lower(username) = lower(?)", (user["username"],)
        ).fetchone()[0] if has_reports else 0
    finally:
        conn.close()

    print(f"user:        {user['username']} (id {user['id']}, created {user['created_at']})")
    print(f"email:       {user['email']}")
    print(f"stripe:      {user['customer_id'] or 'no Stripe customer'}")
    print(f"bug reports: {reports} (kept, but unlinked from this account)")

    if not args.yes:
        try:
            answer = input(f"Delete {user['username']} permanently? [y/N] ")
        except EOFError:
            answer = ""
        if answer.strip().lower() not in ("y", "yes"):
            print("not deleted.")
            return 1

    import database

    if database.delete_user(user["username"]) is None:
        print(f"no user named {args.username!r}")
        return 1

    print(f"deleted {user['username']}.")
    if user["customer_id"]:
        print(f"Now cancel any subscription on Stripe customer {user['customer_id']} and delete it:")
        print(f"  {config.stripe_dashboard_url(user['customer_id'])}")
    else:
        print("no Stripe customer to clean up.")
    # Nothing else holds personal data for long: sessions are stateless tokens
    # that expire on their own, and the user's signed APKs in download_cache
    # carry their username and age out through LRU eviction.
    return 0


TRIAL_METADATA = {"granted_by": "manage.py grant-trial"}


def _print_trial_summary(plan) -> None:
    """What the grant will do, printed before the prompt."""
    user, price = plan.user, plan.price
    print(f"account:    {user.username} <{user.email}> (email verified)")
    print(f"stripe:     {'TEST' if config.STRIPE_TEST_MODE else 'LIVE'} mode")
    if plan.customer_id:
        print(f"customer:   {plan.customer_id}, {plan.how}")
    else:
        print(f"customer:   a new one for {user.email}")
    if plan.note:
        print(f"            ({plan.note})")
    print(f"trial:      until {plan.trial_end:%Y-%m-%d} 00:00 UTC ({plan.days} day(s) from now)")
    print(f"price:      {plan.price_id}" + (f" ({price})" if price else ""))
    if plan.saved_card:
        print(f"at the end: this customer HAS a saved payment method, so from {plan.trial_end:%Y-%m-%d} it will be")
        print(f"            charged{' (' + price + ')' if price else ''} unless the subscription is cancelled first.")
    else:
        print("at the end: with no payment method on file the subscription is cancelled: nothing")
        print("            is charged and the account loses access. If one is added before then,")
        print("            it becomes a paid subscription at the price above.")


def cmd_grant_trial(args) -> int:
    """Give an account a free trial: a Stripe subscription that is trialing until a date.

    The rules live in utils/trials.py, shared with the admin panel. This is the
    command line around them: it prints what will happen, asks first, and adds
    the advice an operator at a shell needs.
    """
    from utils import trials

    now = datetime.now(timezone.utc)
    try:
        trial_end = trials.trial_end(args.days, args.until, now)
    except trials.TrialRefused as refused:
        print(f"not granted: {refused.message}")
        return 1

    db_path = Path(config.DB_PATH)
    if not db_path.exists():
        print(f"no user {args.username!r}: {db_path} does not exist")
        return 1

    import database

    user = database.get_user(args.username)
    if user is None:
        print(f"no user named {args.username!r}")
        return 1

    try:
        plan = trials.plan(user, trial_end, now)
    except trials.TrialRefused as refused:
        print(f"not granted: {refused.message}")
        if refused.reason == trials.TrialRefused.UNVERIFIED:
            print("Ask them to open the link in their verification mail (the Subscription page can send")
            print("a new one), then run this again.")
        elif refused.reason == trials.TrialRefused.LIVE_SUBSCRIPTION and refused.unlinked:
            print("It is not linked to the account yet; the account links it by itself the next time it")
            print("opens the Subscription page or the store app's Plan tab.")
        return 1

    _print_trial_summary(plan)

    if not args.yes:
        try:
            answer = input(f"Grant {user.username} a free trial until {trial_end:%Y-%m-%d}? [y/N] ")
        except EOFError:
            answer = ""
        if answer.strip().lower() not in ("y", "yes"):
            print("not granted.")
            return 1

    try:
        subscription = trials.grant(plan, TRIAL_METADATA["granted_by"])
    except trials.TrialRefused as refused:
        print(f"not granted: {refused.message}")
        if refused.partway:
            # Past the prompt a failure may leave a created, tagged or linked
            # customer behind. That is harmless (it is the account's either
            # way), and a rerun picks it up instead of making another.
            print("Nothing is charged; run this again to retry, it reuses the customer.")
        return 1

    from utils import stripe_utils

    print(f"granted: subscription {stripe_utils.field(subscription, 'id')} "
          f"({stripe_utils.field(subscription, 'status')}) on customer "
          f"{stripe_utils.field(subscription, 'customer')}, linked to {user.username}.")
    print("The site shows it at once. Patched apps already installed may take up to a minute.")
    return 0


def _admins_without_accounts(names) -> list:
    """The listed admin names no account has. All of them when the database can't be read."""
    db_path = Path(config.DB_PATH)
    if not db_path.exists():
        return list(names)
    try:
        conn = _ro(db_path)
        try:
            return [name for name in names
                    if conn.execute("SELECT 1 FROM users WHERE lower(username) = ?", (name,)).fetchone() is None]
        finally:
            conn.close()
    except sqlite3.Error:
        return list(names)


def cmd_doctor(_args) -> int:
    problems = 0
    print(f"environment: {config.ENVIRONMENT}")
    source = " (.dev_env is overriding .env)" if config.DEV_ENV_LOADED else ""
    print(f"stripe mode: {'TEST' if config.STRIPE_TEST_MODE else 'LIVE'}{source}")

    # The two mismatches are not equally bad. Test keys in production take no
    # money and break every checkout loudly; live keys in development charge real
    # cards from a machine nobody treats as dangerous, which is the quiet one.
    if config.STRIPE_TEST_MODE and config.IS_PRODUCTION:
        print(f"[{BAD}] production is on Stripe TEST keys: no real payment can succeed")
        problems += 1
    elif not config.STRIPE_TEST_MODE and not config.IS_PRODUCTION:
        print(f"[{WARN}] development is on Stripe LIVE keys: checkout would charge a real card")
        problems += 1
    else:
        print(f"[{OK}] Stripe keys match the environment")

    if not config.IS_PRODUCTION:
        print(f"[{WARN}] ENVIRONMENT is not 'production': session cookies will be issued")
        print(f"         without Secure and X-Forwarded-For will be ignored.")
        problems += 1
    else:
        print(f"[{OK}] production mode: Secure cookies, proxy headers trusted")

    if config.FRONTEND_URL.startswith("http://") and config.IS_PRODUCTION:
        print(f"[{BAD}] FRONTEND_URL is plaintext in production: {config.FRONTEND_URL}")
        problems += 1
    else:
        print(f"[{OK}] FRONTEND_URL: {config.FRONTEND_URL}")

    insecure = [o for o in config.CORS_ORIGINS if o.startswith("http://")]
    if config.IS_PRODUCTION and insecure:
        print(f"[{WARN}] plaintext CORS origins in production: {', '.join(insecure)}")
        problems += 1
    else:
        print(f"[{OK}] CORS origins: {', '.join(config.CORS_ORIGINS)}")

    if config.APP_JWT_SECRET_KEY == config.JWT_SECRET_KEY:
        print(f"[{BAD}] APP_JWT_SECRET_KEY equals JWT_SECRET_KEY: an APK-embedded")
        print(f"         token would be usable as a browser session cookie.")
        problems += 1
    else:
        print(f"[{OK}] app tokens use a separate signing key")

    for label, path, must_write in (
        ("data dir", config.DATA_DIR, True),
        ("database dir", Path(config.DB_PATH).parent, True),
        ("download cache", config.DOWNLOAD_CACHE_DIR, True),
        ("patched apps", config.PATCHED_APPS_PATH, False),
        ("log file dir", Path(config.LOG_PATH).parent, True),
    ):
        path = Path(path)
        if not path.exists():
            if not must_write:
                print(f"[{BAD}] {label} missing: {path}")
                problems += 1
                continue
            # The app creates these at startup, so only the parent has to be
            # writable today.
            parent = next((p for p in path.parents if p.exists()), None)
            if parent is not None and os.access(parent, os.W_OK):
                print(f"[{OK}] {label}: {path} (created at startup)")
            else:
                print(f"[{BAD}] {label} cannot be created: {path}")
                problems += 1
            continue
        if must_write and not os.access(path, os.W_OK):
            print(f"[{BAD}] {label} not writable: {path}")
            problems += 1
        else:
            print(f"[{OK}] {label}: {path}")

    # On-device patching: the bundle store is only a failure when it exists and
    # the service can't write to it (an upload would 500). Absent is fine: the
    # first upload creates it.
    bundles_dir = Path(config.BUNDLES_DIR)
    if bundles_dir.exists() and (not bundles_dir.is_dir() or not os.access(bundles_dir, os.W_OK)):
        print(f"[{BAD}] BUNDLES_DIR not writable (or not a directory): {bundles_dir}")
        problems += 1

    try:
        import stitch  # noqa: F401
        from stitch.common import FASTSIGNER_PATH
        print(f"[{OK}] stitch importable")
    except ImportError:
        print(f"[{BAD}] stitch 1.4+ not importable; it is not on PyPI - install the")
        print(f"         local checkout with `pip install -e /path/to/Stitch`")
        problems += 1
    else:
        # A non-editable install can drop the executable bit from package data.
        if os.access(FASTSIGNER_PATH, os.X_OK):
            print(f"[{OK}] fastsigner executable: {FASTSIGNER_PATH}")
        else:
            print(f"[{BAD}] fastsigner missing or not executable; every download will fail")
            print(f"         to sign: {FASTSIGNER_PATH}")
            problems += 1

    # Without a keystore, Stitch signs with its bundled debug key, and the
    # Android store app - signed with the real one - refuses every install.
    # systemd does not inherit shell exports, so these belong in .env.
    keystore = os.environ.get("KEYSTORE_PATH")
    if not keystore:
        print(f"[{WARN if not config.IS_PRODUCTION else BAD}] KEYSTORE_PATH is not set: builds are signed with")
        print(f"         Stitch's debug key and the Android app will refuse to install them")
        problems += 1
    elif not Path(keystore).is_file():
        print(f"[{BAD}] KEYSTORE_PATH does not point at a file: {keystore}")
        problems += 1
    elif not os.environ.get("KEYSTORE_PASSWORD"):
        print(f"[{BAD}] KEYSTORE_PATH is set but KEYSTORE_PASSWORD is not")
        problems += 1
    else:
        print(f"[{OK}] signing keystore: {keystore}")

    # Everything below is advisory: printed as warn, never counted in `problems`.
    # doctor is the systemd unit's ExecStartPre, so a non-zero exit keeps the
    # whole site down - a price worth paying for a broken deployment, not for a
    # missing nice-to-have or a trap that has not been sprung yet.
    warnings = 0

    if config.smtp_configured():
        print(f"[{OK}] mail: SMTP as {config.SMTP_USERNAME} via {config.SMTP_HOST}:{config.SMTP_PORT}")
    elif config.IS_PRODUCTION:
        # Still only a warning: existing (grandfathered) customers are unaffected,
        # and taking the whole site down is worse than pausing new subscriptions.
        print(f"[{WARN}] SMTP is not configured: new accounts can't verify their email, so they")
        print(f"         can't subscribe; password reset answers 503, and bug reports are")
        print(f"         stored but not emailed. Set SMTP_USERNAME and SMTP_PASSWORD.")
        warnings += 1
    else:
        print(f"[{OK}] mail: SMTP not configured (fine off production)")

    if config.APP_JWT_SECRET_KEY_EXPLICIT:
        print(f"[{OK}] APP_JWT_SECRET_KEY is set explicitly")
    else:
        print(f"[{WARN}] APP_JWT_SECRET_KEY is derived from JWT_SECRET_KEY: rotating that key")
        print(f"         would silently break every APK already handed out. Pin the current")
        print(f"         value in .env (see README, \"Operations\").")
        warnings += 1

    # Advisory like the rest: a listed name with no account is not a hole
    # (/register refuses listed names), only a panel nobody can open yet.
    admins = sorted(config.ADMIN_USERNAMES)
    if not admins:
        print(f"[{OK}] admin panel: off (ADMIN_USERNAMES is not set)")
    else:
        missing = _admins_without_accounts(admins)
        present = [name for name in admins if name not in missing]
        if present:
            print(f"[{OK}] admin panel: {', '.join(present)}")
        for name in missing:
            print(f"[{WARN}] ADMIN_USERNAMES lists {name!r}, which has no account. /register refuses")
            print(f"         listed names: take it off the list, register it, then list it again.")
            warnings += 1

    # On-device patching settings. Unset ones only switch their endpoints off
    # (503 "Not configured"), so these are warnings, never failures.
    for name, value in (("CRAWLER_SECRET", config.CRAWLER_SECRET),
                        ("BUNDLE_UPLOAD_SECRET", config.BUNDLE_UPLOAD_SECRET)):
        if value:
            print(f"[{OK}] {name} is set")
        else:
            print(f"[{WARN}] {name} is not set: the matching /api/internal endpoint answers 503")
            warnings += 1
    if config.SIGNING_KEYS_MASTER_KEY:
        print(f"[{OK}] SIGNING_KEYS_MASTER_KEY is set (back it up apart from the database)")
    else:
        print(f"[{WARN}] SIGNING_KEYS_MASTER_KEY is not set: GET /api/signing-key answers 503")
        warnings += 1
    if Path(config.BUNDLE_PUBLIC_KEY_PATH).is_file():
        print(f"[{OK}] bundle public key: {config.BUNDLE_PUBLIC_KEY_PATH}")
    else:
        print(f"[{WARN}] BUNDLE_PUBLIC_KEY_PATH does not point at a file: bundle uploads answer 503")
        print(f"         ({config.BUNDLE_PUBLIC_KEY_PATH})")
        warnings += 1
    try:
        import stitch.bundle  # noqa: F401
    except ImportError:
        print(f"[{WARN}] stitch.bundle is not importable (Stitch >= 1.5.0 is needed): bundle uploads answer 503")
        warnings += 1
    warnings += _doctor_signing_keys()
    warnings += _doctor_on_device()
    problems += _doctor_works_with()
    warnings += _doctor_generic()

    cutoff = config.LEGACY_APP_TOKEN_CUTOFF
    if cutoff is None:
        print(f"[{OK}] legacy app tokens: accepted (LEGACY_APP_TOKEN_CUTOFF is not set)")
    elif datetime.now(timezone.utc) >= cutoff:
        print(f"[{OK}] legacy app tokens: refused since {cutoff.date().isoformat()}")
    else:
        print(f"[{OK}] legacy app tokens: accepted until {cutoff.date().isoformat()}")

    print()
    if problems:
        print(f"{problems} issue(s) to resolve before serving traffic.")
    else:
        print("all checks passed." + (f" {warnings} warning(s) above." if warnings else ""))
    return 1 if problems else 0


def _doctor_signing_keys() -> int:
    """Warn when the master key can't open a stored signing key (wrong or restored-from-elsewhere key)."""
    db_path = Path(config.DB_PATH)
    if not config.SIGNING_KEYS_MASTER_KEY or not db_path.exists():
        return 0
    try:
        conn = _ro(db_path)
        try:
            row = conn.execute("SELECT user_id, nonce, ciphertext FROM signing_keys LIMIT 1").fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return 0  # no table yet: nothing stored
    if row is None:
        return 0
    from utils import signing_keys
    try:
        signing_keys.open_sealed(row["user_id"], row["nonce"], row["ciphertext"])
    except Exception:
        print(f"[{WARN}] SIGNING_KEYS_MASTER_KEY does not match stored signing keys: GET /api/signing-key")
        print(f"         answers 500 until the original key is restored")
        return 1
    print(f"[{OK}] SIGNING_KEYS_MASTER_KEY opens the stored signing keys")
    return 0


def _doctor_works_with() -> int:
    """Fail when the hand-edited Works-with file is invalid (the endpoint then serves an empty list)."""
    from utils import works_with
    path = config.GENERIC_WORKS_WITH_PATH
    try:
        entries = works_with.parse(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, works_with.WorksWithError) as e:
        print(f"[{BAD}] Works-with list {path}: {e}")
        return 1
    print(f"[{OK}] Works-with list: {len(entries)} entr{'y' if len(entries) == 1 else 'ies'}")
    return 0


def _doctor_generic() -> int:
    """Warn when GENERIC_PATCHING is on but no generic bundle has been uploaded."""
    if not config.GENERIC_PATCHING:
        return 0
    from utils import patch_store
    try:
        uploaded = patch_store.newest_bundle(config.GENERIC_PACKAGE) is not None
    except sqlite3.Error:
        uploaded = False
    if uploaded:
        print(f"[{OK}] GENERIC_PATCHING is on and a generic bundle is uploaded")
        return 0
    print(f"[{WARN}] GENERIC_PATCHING is on but no generic bundle is uploaded: the store app")
    print(f"         would find nothing to apply (upload the bundle first)")
    return 1


def _doctor_on_device() -> int:
    """Warn about ON_DEVICE_PACKAGES entries that are unknown or have nothing to offer yet."""
    warnings = 0
    db_path = Path(config.DB_PATH)
    for package in sorted(config.ON_DEVICE_PACKAGES):
        if package not in config.APPS_BY_PACKAGE:
            print(f"[{WARN}] ON_DEVICE_PACKAGES lists {package!r}, which is not in the app catalogue")
            warnings += 1
            continue
        try:
            from utils import patch_store
            advertisable = db_path.exists() and patch_store.latest_record(
                package, 10**12, config.OFFICIAL_SOURCES.get(package)) is not None
        except sqlite3.Error:
            advertisable = False
        if advertisable:
            print(f"[{OK}] on-device: {package} has a supported build and bundle")
        else:
            print(f"[{WARN}] on-device: {package} has no supported record with a bundle: the store")
            print(f"         app would find nothing to install (upload the bundle and records first)")
            warnings += 1
    return warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check", help="read-only: report what the migration will do")
    migrate = sub.add_parser("migrate", help="apply the schema migration (idempotent)")
    migrate.add_argument("--dry-run", action="store_true", help="same as `check`")
    sub.add_parser("doctor", help="validate configuration for production")
    bug_reports = sub.add_parser("bug-reports", help="list recent bug reports, newest first (read-only)")
    bug_reports.add_argument("--limit", type=int, default=20, help="how many to show (default 20)")
    delete_user = sub.add_parser("delete-user", help="delete an account on request (asks first)")
    delete_user.add_argument("username")
    delete_user.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    grant_trial = sub.add_parser("grant-trial", help="give an account a free trial on Stripe (asks first)")
    grant_trial.add_argument("username")
    trial_length = grant_trial.add_mutually_exclusive_group(required=True)
    trial_length.add_argument("--until", metavar="YYYY-MM-DD", help="the trial ends at 00:00 UTC on this day")
    trial_length.add_argument("--days", type=int, metavar="N", help="the trial ends at 00:00 UTC, N days from today")
    grant_trial.add_argument("--yes", action="store_true", help="do not ask for confirmation")

    args = parser.parse_args()
    return {
        "check": cmd_check,
        "migrate": cmd_migrate,
        "doctor": cmd_doctor,
        "bug-reports": cmd_bug_reports,
        "delete-user": cmd_delete_user,
        "grant-trial": cmd_grant_trial,
    }[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
