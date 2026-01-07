from typing import Callable
from functools import wraps
from config import APPS


def is_valid_package(package_name: str) -> bool:
    return any(map(lambda x: x.package == package_name, APPS))


def check_package_name(func) -> Callable:
    @wraps(func)
    def wrapper(*args, **kwargs):
        package_name = kwargs.get('package_name')
        if not is_valid_package(package_name):
            return {"error": "Invalid package name"}
        return func(*args, **kwargs)
    return wrapper