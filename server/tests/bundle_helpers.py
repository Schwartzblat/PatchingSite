"""Build small signed patch bundles for tests (mirrors Stitch's own test helpers)."""
import json
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from pyaxml import AXML
from stitch.bundle import BundleModule, build_bundle

ANDROID_NS = "http://schemas.android.com/apk/res/android"

FEATURES_JSON = json.dumps({
    "required_keys": ["a"],
    "official_cert_sha256": [],
    "features": [{"id": "f", "title": "F", "keys": ["a"]}],
})


def ec_keypair(tmp_path, name="key.pem"):
    """Return (private key path, public key PEM bytes)."""
    key = ec.generate_private_key(ec.SECP256R1())
    path = tmp_path / name
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                           serialization.PublicFormat.SubjectPublicKeyInfo)
    return path, public


def build_module_apk(path, inner, package="com.mod"):
    axml = AXML()
    axml.from_xml(f'<manifest xmlns:android="{ANDROID_NS}" package="{package}">{inner}</manifest>')
    axml.compute()
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("AndroidManifest.xml", axml.pack())
    return path


def make_bundle(tmp_path, key_pem, package="com.whatsapp", patcher_version="3.0.0",
                min_store_version_code=7, name="b.zip"):
    """Build a signed bundle in tmp_path; return (path, meta)."""
    hook = build_module_apk(
        tmp_path / "hook.apk",
        '<uses-sdk android:minSdkVersion="33"/><uses-permission android:name="android.permission.INTERNET"/>'
        '<application/>', package="com.smali_generator")
    features = tmp_path / "features.json"
    features.write_text(FEATURES_JSON)
    out = tmp_path / name
    meta = build_bundle(out, package, patcher_version, [BundleModule("module.apk", hook)],
                        ["com.smali_generator.InitProvider"], features, key_pem,
                        min_store_version_code=min_store_version_code)
    return out, meta


GENERIC_CATALOG = json.dumps({"version": 1, "ad_networks": []}).encode()


def make_generic_bundle(tmp_path, key_pem, patcher_version="1.0.0", min_store_version_code=0,
                        name="g.zip", with_features=False, with_catalog=True):
    """Build a signed generic ad-remover bundle (package `generic`); return (path, meta)."""
    hook = build_module_apk(
        tmp_path / "generic-hook.apk",
        '<uses-sdk android:minSdkVersion="33"/><application/>', package="com.smali_generator")
    features = None
    if with_features:
        features = tmp_path / "features.json"
        features.write_text(FEATURES_JSON)
    extra = {"catalog.json": GENERIC_CATALOG} if with_catalog else None
    out = tmp_path / name
    meta = build_bundle(out, "generic", patcher_version, [BundleModule("module.apk", hook)],
                        ["generic.com.smali_generator.InitProviderGeneric"], features, key_pem,
                        min_store_version_code=min_store_version_code, extra_files=extra)
    return out, meta


def resign_bundle(src, dest, key_pem, mutate):
    """Copy a bundle with bundle.json altered by `mutate(meta)` and re-signed with key_pem."""
    with zipfile.ZipFile(src) as zf:
        entries = {n: zf.read(n) for n in zf.namelist()}
    meta = json.loads(entries["bundle.json"])
    mutate(meta)
    meta_bytes = json.dumps(meta, sort_keys=True).encode()
    key = serialization.load_pem_private_key(key_pem.read_bytes(), None)
    entries["bundle.json"] = meta_bytes
    entries["bundle.sig"] = key.sign(meta_bytes, ec.ECDSA(hashes.SHA256()))
    with zipfile.ZipFile(dest, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return dest
