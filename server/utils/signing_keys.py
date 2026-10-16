"""Per-user APK signing keys.

A user's phone signs their patched apps with this key, so losing or replacing
it forces them through a full chat move. `get_or_create` is therefore
first-writer-wins: the row that lands in the table first is the key, forever.
The PKCS#12 and its password are sealed with AES-GCM under the server's master
key and never logged.
"""
import base64
import hashlib
import json
import os
import secrets
from dataclasses import dataclass
from datetime import datetime, timezone

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

import config
from utils import patch_store

ALIAS = "androidmod"
NOT_BEFORE = datetime(2026, 1, 1, tzinfo=timezone.utc)
NOT_AFTER = datetime(2056, 1, 1, tzinfo=timezone.utc)


class NotConfigured(Exception):
    """SIGNING_KEYS_MASTER_KEY is not set."""


@dataclass(frozen=True)
class GeneratedKey:
    pkcs12: bytes
    password: str
    cert_sha256: str
    cert_sha1: str

    def __repr__(self) -> str:
        return "GeneratedKey(<redacted>)"


def generate(user_id: int) -> GeneratedKey:
    private = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, f"androidmod user {user_id}"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "androidmod"),
    ])
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(private.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(NOT_BEFORE).not_valid_after(NOT_AFTER)
            .sign(private, hashes.SHA256()))
    der = cert.public_bytes(serialization.Encoding.DER)
    password = secrets.token_urlsafe(32)
    blob = pkcs12.serialize_key_and_certificates(
        ALIAS.encode(), private, cert, None, serialization.BestAvailableEncryption(password.encode()))
    return GeneratedKey(blob, password, hashlib.sha256(der).hexdigest(), hashlib.sha1(der).hexdigest())


def _aead() -> AESGCM:
    if config.SIGNING_KEYS_MASTER_KEY is None:
        raise NotConfigured()
    return AESGCM(config.SIGNING_KEYS_MASTER_KEY)


def _aad(user_id: int) -> bytes:
    return b"signing-key:%d" % user_id


def seal(user_id: int, key: GeneratedKey) -> tuple[bytes, bytes]:
    plaintext = json.dumps({
        "pkcs12": base64.b64encode(key.pkcs12).decode(), "password": key.password,
        "cert_sha256": key.cert_sha256, "cert_sha1": key.cert_sha1,
    }).encode()
    nonce = os.urandom(12)
    return nonce, _aead().encrypt(nonce, plaintext, _aad(user_id))


def open_sealed(user_id: int, nonce: bytes, ciphertext: bytes) -> GeneratedKey:
    data = json.loads(_aead().decrypt(bytes(nonce), bytes(ciphertext), _aad(user_id)))
    return GeneratedKey(base64.b64decode(data["pkcs12"]), data["password"],
                        data["cert_sha256"], data["cert_sha1"])


def get_or_create(user_id: int) -> GeneratedKey:
    _aead()  # fail before generating anything when the master key is missing
    row = patch_store.get_signing_key(user_id)
    if row is None:
        key = generate(user_id)
        nonce, ciphertext = seal(user_id, key)
        patch_store.insert_signing_key_if_absent(user_id, nonce, ciphertext, key.cert_sha256, key.cert_sha1)
        row = patch_store.get_signing_key(user_id)  # whichever writer won
    return open_sealed(user_id, row["nonce"], row["ciphertext"])
