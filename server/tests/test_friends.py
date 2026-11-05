"""The friends-only catalogue: Moovit, 12+, Reshet 13+ and OkCupid exist only for flagged accounts."""
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import config
import database
import utils.utils
from api import apps
from tests.test_downloads import make_version_dir
from utils import downloads
from utils.jwt_utils import CREDENTIALS_EXCEPTION, TokenData, get_optional_token_data, get_token_data
from utils.stripe_utils import SubscriptionInfo, get_subscription_info

FRIEND_PACKAGES = ["com.tranzmate", "com.keshet.mako.VOD", "com.applicaster.iReshet", "com.okcupid.okcupid"]
UNKNOWN = {"detail": "Unknown package"}


def register(name):
    assert database.register_user(database.RegisterRequest(
        username=name, email=f"{name}@example.com", password="correct horse battery"))


def token_for(name):
    return TokenData(username=name, email=f"{name}@example.com", customer_id=f"cus_{name}",
                     exp=datetime.now(timezone.utc) + timedelta(hours=1))


@pytest.fixture(autouse=True)
def clean_registry():
    downloads.reset_for_tests()
    yield
    downloads.reset_for_tests()


@pytest.fixture
def cache_dir(monkeypatch, tmp_path):
    directory = tmp_path / "download_cache"
    directory.mkdir()
    monkeypatch.setattr(config, "DOWNLOAD_CACHE_DIR", directory)
    return directory


@pytest.fixture
def app_for(tmp_db, patched_apps_dir, cache_dir):
    register("alice")
    register("bob")
    assert database.set_friend("alice", True)

    def build(identity=None, overrides=True):
        app = FastAPI()
        app.include_router(apps.router, prefix="/api")
        if overrides:
            if identity is None:
                def guest():
                    raise CREDENTIALS_EXCEPTION
                app.dependency_overrides[get_optional_token_data] = lambda: None
                app.dependency_overrides[get_token_data] = guest
            else:
                app.dependency_overrides[get_optional_token_data] = lambda: token_for(identity)
                app.dependency_overrides[get_token_data] = lambda: token_for(identity)
            app.dependency_overrides[get_subscription_info] = lambda: SubscriptionInfo(
                client_id="cus_x", email="x@example.com", status="active")
        return TestClient(app)

    return build


def test_public_catalogue_is_unchanged_for_guests_and_non_friends(app_for):
    for identity in (None, "bob"):
        body = app_for(identity).get("/api/apps").json()
        assert [a["package"] for a in body] == ["com.whatsapp"]
        assert set(body[0]) == {"key", "name", "package", "features", "on_device"}


def test_friends_get_the_four_apps_after_whatsapp(app_for):
    body = app_for("alice").get("/api/apps").json()
    assert [a["package"] for a in body] == ["com.whatsapp"] + FRIEND_PACKAGES
    moovit = body[1]
    assert moovit["friends_only"] is True
    assert moovit["name_he"] == "מוביט"
    assert moovit["features_he"] == ["בלי פרסומות", "מיקום חי"]
    assert moovit["icon"] == "/api/apps/com.tranzmate/icon"
    assert moovit["on_device"] is False
    assert "friends_only" not in body[0]


def test_a_friends_only_package_is_unknown_to_non_friends(app_for, patched_apps_dir):
    make_version_dir(patched_apps_dir, "com.tranzmate", "5.201.6.1811")
    for identity in (None, "bob"):
        c = app_for(identity)
        expected = c.get("/api/list/com.example.never")
        assert expected.status_code == 404 and expected.json() == UNKNOWN
        responses = [c.get("/api/list/com.tranzmate"), c.get("/api/apps/com.tranzmate/icon")]
        if identity == "bob":
            responses.append(c.post("/api/downloads",
                                    json={"package": "com.tranzmate", "version": "5.201.6.1811"}))
        for response in responses:
            assert response.status_code == expected.status_code
            assert response.json() == expected.json()


def test_friends_list_and_download_friends_only_builds(app_for, patched_apps_dir):
    make_version_dir(patched_apps_dir, "com.tranzmate", "5.201.6.1811")
    c = app_for("alice")
    listing = c.get("/api/list/com.tranzmate")
    assert listing.status_code == 200
    assert [v["version"] for v in listing.json()] == ["5.201.6.1811"]
    response = c.post("/api/downloads", json={"package": "com.tranzmate", "version": "5.201.6.1811"})
    assert response.status_code == 202


def test_a_friend_with_no_builds_yet_gets_an_empty_list(app_for):
    response = app_for("alice").get("/api/list/com.okcupid.okcupid")
    assert response.status_code == 200
    assert response.json() == []


def test_friend_icon_is_served_privately(app_for):
    response = app_for("alice").get("/api/apps/com.tranzmate/icon")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/webp")
    assert response.content == (config.FRIEND_ICONS_DIR / "moovit.webp").read_bytes()
    assert response.headers["cache-control"] == "private, max-age=86400"


def test_icon_path_is_not_traversable(app_for):
    c = app_for("alice")
    assert c.get("/api/apps/..%2Fconfig.py/icon").status_code == 404
    assert c.get("/api/apps/com.whatsapp/icon").status_code == 404


def test_unfriending_takes_effect_on_the_next_request(app_for):
    c = app_for("alice")
    assert len(c.get("/api/apps").json()) == 5
    database.set_friend("alice", False)
    assert [a["package"] for a in c.get("/api/apps").json()] == ["com.whatsapp"]
    assert c.get("/api/list/com.tranzmate").status_code == 404
    assert c.get("/api/apps/com.tranzmate/icon").status_code == 404


def test_a_deleted_account_is_not_a_friend(app_for):
    c = app_for("ghost")
    assert [a["package"] for a in c.get("/api/apps").json()] == ["com.whatsapp"]
    assert c.get("/api/list/com.tranzmate").status_code == 404
    assert c.get("/api/apps/com.tranzmate/icon").status_code == 404


def test_a_bad_token_reads_as_a_guest_on_the_catalogue(app_for):
    c = app_for(overrides=False)
    response = c.get("/api/apps", headers={"Authorization": "Bearer garbage"})
    assert response.status_code == 200
    assert [a["package"] for a in response.json()] == ["com.whatsapp"]


def test_every_friend_app_has_an_icon_and_hebrew_text():
    for app in config.FRIEND_APPS:
        assert (config.FRIEND_ICONS_DIR / f"{app.key}.webp").is_file()
        assert app.name_he
        assert len(app.features_he) == len(app.features)


def test_friend_apps_are_not_public_packages():
    assert config.FRIEND_APPS_BY_PACKAGE.keys().isdisjoint(config.APPS_BY_PACKAGE)
    assert not utils.utils.is_valid_package("com.tranzmate")
