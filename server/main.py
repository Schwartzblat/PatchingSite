import os
from pathlib import Path
from fastapi import FastAPI
from config import PATCHED_APPS_PATH, APPS
from utils import is_valid_package, check_package_name

app = FastAPI()


@app.get("/list/{package_name}")
@check_package_name
def list_versions(package_name: str):
    return list(os.listdir(PATCHED_APPS_PATH / Path(package_name).name))

@app.get("/patch/{package_name}/{version}")
@check_package_name
def patch_version(package_name: str, version: str):
    pass

@app.get("/path/{package_name}/latest")
@check_package_name
def patch_latest(package_name: str):
    pass