"""The database helpers behind the admin panel (api/admin.py) and manage.py delete-user."""
import sqlite3
from datetime import datetime, timezone

import database

PASSWORD = "correct horse battery"


def register(username, email=None):
    assert database.register_user(database.RegisterRequest(
        username=username, email=email or f"{username}@example.com", password=PASSWORD))
    return database.get_user(username)


# --- delete_user --------------------------------------------------------------------

def test_delete_user_returns_the_deleted_account(tmp_db):
    register("alice")
    assert database.update_user_customer_id("username", "alice", "cus_alice")

    deleted = database.delete_user("ALICE")

    assert (deleted.username, deleted.customer_id) == ("alice", "cus_alice")
    assert database.get_user("alice") is None


def test_delete_user_unlinks_the_reports_and_keeps_their_text(tmp_db):
    register("alice")
    report_id = database.add_bug_report(source="web", message="It broke", email="reply@example.com",
                                        username="alice", client_ip="198.51.100.4", context={},
                                        created_at=datetime.now(timezone.utc))

    database.delete_user("alice")

    conn = sqlite3.connect(tmp_db)
    row = conn.execute("SELECT username, email, client_ip, message FROM bug_reports WHERE id = ?",
                       (report_id,)).fetchone()
    conn.close()
    assert row == (None, None, None, "It broke")


def test_delete_user_of_an_unknown_name_is_none(tmp_db):
    assert database.delete_user("ghost") is None


# --- list_users ---------------------------------------------------------------------

def test_list_users_is_newest_first_and_counts_past_the_limit(tmp_db):
    for name in ("alice", "bob", "carol"):
        register(name)

    users, total = database.list_users("", 2)

    assert [user.username for user in users] == ["carol", "bob"]
    assert total == 3


def test_list_users_matches_a_literal_substring_in_any_case(tmp_db):
    register("alice", "alice@mail.example.com")
    register("bob_99", "robert@example.com")
    register("bobby", "bobby@example.com")

    def names(q):
        return [user.username for user in database.list_users(q, 50)[0]]

    assert names("ALI") == ["alice"]
    assert names("ROBERT@") == ["bob_99"]
    assert names("ob_") == ["bob_99"]
    # LIKE's wildcards are plain characters here: "bo_" would match "bob"
    # and "%" everything.
    assert names("bo_") == []
    assert names("%") == []
    assert names("") == ["bobby", "bob_99", "alice"]


# --- set_email ----------------------------------------------------------------------

def test_set_email_stamps_or_clears_verification(tmp_db):
    register("alice")
    now = datetime(2026, 10, 10, 9, tzinfo=timezone.utc)

    assert database.set_email("alice", "new@example.com", now) is database.EmailChangeResult.CHANGED
    user = database.get_user("alice")
    assert (user.email, user.email_verified_at) == ("new@example.com", now)

    assert database.set_email("alice", "newer@example.com", None) is database.EmailChangeResult.CHANGED
    assert database.get_user("alice").email_verified_at is None


def test_set_email_refuses_another_accounts_address_in_any_case(tmp_db):
    register("alice")
    register("bob")
    assert database.set_email("alice", "BOB@example.com", None) is database.EmailChangeResult.TAKEN
    assert database.get_user("alice").email == "alice@example.com"


def test_set_email_refuses_a_taken_address_without_the_unique_index(tmp_db):
    """Where historical duplicates blocked the index (database.init), the check
    inside the transaction is all that keeps two accounts off one address."""
    register("alice")
    register("bob")
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP INDEX idx_users_email_unique")
    conn.commit()
    conn.close()

    assert database.set_email("alice", "bob@example.com", None) is database.EmailChangeResult.TAKEN
    assert database.get_user("alice").email == "alice@example.com"


def test_set_email_may_change_only_the_case_of_its_own_address(tmp_db):
    register("alice")
    assert database.set_email("alice", "Alice@example.com", None) is database.EmailChangeResult.CHANGED
    assert database.get_user("alice").email == "Alice@example.com"


def test_set_email_of_an_unknown_name(tmp_db):
    assert database.set_email("ghost", "ghost@example.com", None) is database.EmailChangeResult.USER_NOT_FOUND


# --- clear_devices ------------------------------------------------------------------

def test_clear_devices_keeps_the_users_own_reset_stamp(tmp_db):
    register("alice")
    assert database.update_user_device_fingerprints("alice", "phone_sku")
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET devices_reset_at = '2026-10-01T00:00:00Z'")
    conn.commit()
    conn.close()

    assert database.clear_devices("ALICE") is True

    user = database.get_user("alice")
    assert user.device_fingerprints == []
    assert user.devices_reset_at == datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert database.clear_devices("ghost") is False
