import inspect
from contextlib import asynccontextmanager

from fastapi import FastAPI
from starlette.middleware.cors import CORSMiddleware

import config
import database
from api import admin, apps, auth, bug_reports, email_verification, password_reset, payment, store, users
from utils.downloads import start_workers, stop_workers
from utils.logging_config import logger


async def _call(func):
    result = func()
    if inspect.isawaitable(result):
        await result


@asynccontextmanager
async def lifespan(_app: FastAPI):
    logger.info('Starting server (environment=%s)', config.ENVIRONMENT)

    # Created up front so they exist with the service account's ownership from
    # the first boot, rather than appearing on the first download.
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    config.DOWNLOAD_CACHE_DIR.mkdir(parents=True, exist_ok=True)

    database.init()
    # Deliberately unguarded: downloads are the product. A deploy that cannot
    # start its workers must fail loudly here, not serve a site where every
    # download silently errors.
    await _call(start_workers)

    try:
        yield
    finally:
        try:
            await _call(stop_workers)
        except Exception:
            logger.exception('Download workers failed to stop cleanly')
        logger.info('Server stopped')


app = FastAPI(lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=list(config.CORS_ORIGINS),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok", "environment": config.ENVIRONMENT}


app.include_router(apps.router, prefix="/api")
app.include_router(auth.router, prefix="/api")
app.include_router(password_reset.router, prefix="/api")
app.include_router(email_verification.router, prefix="/api")
app.include_router(users.router, prefix="/api")
app.include_router(payment.router, prefix="/api")
app.include_router(store.router, prefix="/api")
app.include_router(bug_reports.router, prefix="/api")
app.include_router(admin.router, prefix="/api")
