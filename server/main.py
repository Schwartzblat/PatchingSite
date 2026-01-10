import os
from pathlib import Path
from fastapi import FastAPI
from starlette.middleware.cors import CORSMiddleware

from config import PATCHED_APPS_PATH, APPS
from utils import is_valid_package, check_package_name

app = FastAPI()

origins = [
    "http://localhost:3000",  # Example frontend origin
    "https://www.example.com", # Example production origin
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