"""The store app download: a plain file under PATCHED_APPS_PATH, served as-is."""
import io
import os
import zipfile

import pytest
from pyaxml import AXML, ET
from starlette.testclient import TestClient

import config


def apk_bytes(version_code: int, version_name: str) -> bytes:
    """A zip whose only entry is a real binary AndroidManifest.xml.

    Enough for the version lookup, which reads nothing else. pyaxml ships with
    Stitch, so building the manifest needs no Android SDK.
    """
    manifest = ET.fromstring(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        f'package="site.androidmod.store" android:versionCode="{version_code}" '
        f'android:versionName="{version_name}"><application/></manifest>'.encode()
    )
    axml = AXML()
    axml.from_xml(manifest)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", axml.pack())
    return buffer.getvalue()


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as client:
        yield client


@pytest.fixture
def store_apk(monkeypatch, tmp_path):
    path = tmp_path / "store.apk"
    monkeypatch.setattr(config, "STORE_APK_PATH", path)
    return path


def test_store_is_reported_unavailable_until_deployed(client, store_apk):
    assert client.get("/api/store").json() == {"available": False}
    assert client.get("/api/store/download").status_code == 404


def test_deployed_store_apk_is_served_without_auth(client, store_apk):
    store_apk.write_bytes(b"PK\x03\x04 fake apk")

    info = client.get("/api/store").json()
    assert info["available"] is True
    assert info["size"] == store_apk.stat().st_size

    response = client.get("/api/store/download")
    assert response.status_code == 200
    assert response.content == store_apk.read_bytes()
    assert response.headers["content-type"] == "application/vnd.android.package-archive"
    assert info["filename"] in response.headers["content-disposition"]


def test_store_path_defaults_to_the_patched_apps_root():
    assert config.STORE_APK_PATH == config.PATCHED_APPS_PATH / "store.apk"


def test_store_reports_the_version_inside_the_apk(client, store_apk):
    store_apk.write_bytes(apk_bytes(389000, "1.2"))

    info = client.get("/api/store").json()

    assert info["version_code"] == 389000
    assert info["version_name"] == "1.2"


def test_unreadable_apk_reports_no_version(client, store_apk):
    # Still downloadable, but the app cannot order it against its own build, so
    # it must not be offered as an update: null, never a guessed number.
    store_apk.write_bytes(b"PK\x03\x04 fake apk")

    info = client.get("/api/store").json()

    assert info["available"] is True
    assert info["version_code"] is None
    assert info["version_name"] is None


def test_replacing_the_apk_reports_the_new_version(client, store_apk):
    store_apk.write_bytes(apk_bytes(389000, "1.2"))
    assert client.get("/api/store").json()["version_code"] == 389000

    # Same size and same mtime second would defeat a coarse cache key; the new
    # build here differs only in its digits, so pin the mtime forward explicitly.
    store_apk.write_bytes(apk_bytes(389001, "1.3"))
    stat = store_apk.stat()
    os.utime(store_apk, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))

    info = client.get("/api/store").json()
    assert info["version_code"] == 389001
    assert info["version_name"] == "1.3"


def test_the_store_download_resumes_with_a_range_request(client, store_apk):
    """The Android client resumes an interrupted transfer with Range."""
    payload = bytes(range(256)) * 40
    store_apk.write_bytes(payload)

    full = client.get("/api/store/download")
    assert full.headers.get("accept-ranges") == "bytes"

    head = client.get("/api/store/download", headers={"Range": "bytes=0-99"})
    assert head.status_code == 206
    assert head.headers["content-range"] == f"bytes 0-99/{len(payload)}"
    assert head.content == payload[:100]

    rest = client.get("/api/store/download", headers={"Range": "bytes=100-"})
    assert rest.status_code == 206
    assert rest.headers["content-range"] == f"bytes 100-{len(payload) - 1}/{len(payload)}"
    assert head.content + rest.content == payload
