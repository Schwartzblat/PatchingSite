"""Who may see the friends-only apps (config.FRIEND_APPS).

The flag is read from the database on every call, so unfriending (or deleting
the account) takes effect on the next request even while the session lives.
"""
import config
import database
from utils.utils import PACKAGE_NOT_FOUND, validate_package_name


def is_friend(username: str | None) -> bool:
    if not username:
        return False
    user = database.get_user(username)
    return user is not None and user.is_friend


def visible_apps(username: str | None) -> tuple:
    if is_friend(username):
        return config.APPS + config.FRIEND_APPS
    return config.APPS


def require_visible_package(package_name: str, username: str | None) -> str:
    """The package name, or the same 404 an unknown package gets."""
    if package_name in config.FRIEND_APPS_BY_PACKAGE:
        if not is_friend(username):
            raise PACKAGE_NOT_FOUND
        return package_name
    return validate_package_name(package_name)
