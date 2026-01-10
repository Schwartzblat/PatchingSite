import os
from pathlib import Path
from typing import List
import datetime
from fastapi import FastAPI
from starlette.middleware.cors import CORSMiddleware
from starlette.responses import FileResponse

from config import PATCHED_APPS_PATH, APPS
from utils import is_valid_package, check_package_name

app = FastAPI()

origins = [
    "http://localhost:3000",  # Example frontend origin
    "https://www.example.com",  # Example production origin
    '*'  # Allow all origins (for development purposes)
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],  # Allows all standard methods (GET, POST, PUT, DELETE, OPTIONS, etc.)
    allow_headers=["*"],  # Allows all headers
)


@app.get("/list/{package_name}")
@check_package_name
def list_versions(package_name: str) -> List[dict]:
    """
    returns sorted [{
        version: str,
        type: str,  # apk or xapk
        filename: str,
        size: int,  # in bytes
        updated_at: str,
    }]
    :param package_name:
    :return:
    """
    package_path = PATCHED_APPS_PATH / Path(package_name).name
    versions = []
    for version_directory in os.listdir(package_path):
        apk_file = os.listdir(package_path / version_directory)[0]
        versions.append({
            'version': version_directory,
            'type': apk_file.split('.')[-1],
            'filename': apk_file,
            'size': os.path.getsize(package_path / version_directory / apk_file),
            'updated_at': datetime.datetime.fromtimestamp(
                os.path.getmtime(package_path / version_directory / apk_file)).strftime('%Y-%m-%d %H:%M'),
            'href': f'/download/{package_name}/{version_directory}'
        })

    return sorted(versions, key=lambda x: x['version'], reverse=True)


@app.get("/patch/{package_name}/{version}")
@check_package_name
def patch_version(package_name: str, version: str):
    pass


@app.get("/path/{package_name}/latest")
@check_package_name
def patch_latest(package_name: str):
    pass


@app.get("/download/{package_name}/{version}")
@check_package_name
def download_version(package_name: str, version: str):
    version_path = PATCHED_APPS_PATH / Path(package_name).name / Path(version).name
    if not version_path.exists():
        return {"error": "Version not found"}

    apk_file = version_path / os.listdir(version_path)[0]
    if not apk_file.exists() or not apk_file.is_file():
        return {"error": "File not found"}
    return FileResponse(str(apk_file), media_type='application/octet-stream')
