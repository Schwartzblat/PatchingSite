import functools
import hashlib
import hmac
import os
import secrets
from typing import Tuple

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from config import LEGACY_NUMBER_OF_HASHES

SCHEME_ARGON2 = "argon2"
SCHEME_LEGACY = "legacy"

MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 128

_hasher = PasswordHasher()


def hash_password(password: str) -> Tuple[str, str]:
    """Hash a password with Argon2id. Returns (hash, scheme)."""
    return _hasher.hash(password), SCHEME_ARGON2


def legacy_hash(password: str, salt: str) -> str:
    """Reproduce the original scheme: LEGACY_NUMBER_OF_HASHES rounds of salted SHA-512.

    Only used to verify pre-migration passwords. Never used to store new ones.
    """
    digest = password
    for _ in range(LEGACY_NUMBER_OF_HASHES):
        digest = hashlib.sha512((salt + digest).encode()).hexdigest()
    return digest


def verify_password(password: str, stored_hash: str, salt: str, scheme: str) -> bool:
    """Constant-time verification against whichever scheme the row was stored with."""
    if scheme == SCHEME_ARGON2:
        try:
            return _hasher.verify(stored_hash, password)
        except (VerifyMismatchError, VerificationError, InvalidHashError):
            return False
    return hmac.compare_digest(legacy_hash(password, salt or ""), stored_hash or "")


@functools.cache
def _dummy_hash() -> str:
    """An Argon2 hash of a random secret nobody knows, with the live parameters."""
    return _hasher.hash(secrets.token_hex(16))


def spend_a_verify(password: str) -> None:
    """Pay for one Argon2 verify that cannot succeed.

    For logins that would otherwise skip Argon2 entirely: an unknown username,
    or a legacy (SHA-512) account. Both answered ~50 ms faster than a login to
    an Argon2 account, so response time alone told an attacker which usernames
    exist - an oracle the identical 401s were meant to close.
    """
    try:
        _hasher.verify(_dummy_hash(), password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        pass


def needs_rehash(stored_hash: str, scheme: str) -> bool:
    """True when a successful login should transparently upgrade the stored hash."""
    if scheme != SCHEME_ARGON2:
        return True
    try:
        return _hasher.check_needs_rehash(stored_hash)
    except InvalidHashError:
        return True


def new_salt() -> str:
    """Per-call random salt. The original bug was making this a default argument."""
    return hashlib.sha512(os.urandom(32)).hexdigest()
