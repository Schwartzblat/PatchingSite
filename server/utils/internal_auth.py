"""Shared-secret auth for machine callers (the crawler, the bundle uploader)."""
import hmac
from typing import Callable, Optional

from fastapi import Header, HTTPException

import config


def require_internal(secret_name: str) -> Callable:
    """Dependency factory: check `X-Internal-Token` against `config.<secret_name>`.

    The secret is read per request so a missing one answers 503 instead of
    failing at import, and tests can change it.
    """
    def dependency(x_internal_token: Optional[str] = Header(default=None)) -> None:
        secret = getattr(config, secret_name, None)
        if not secret:
            raise HTTPException(status_code=503, detail="Not configured")
        if x_internal_token is None or not hmac.compare_digest(
                x_internal_token.encode(), secret.encode()):
            raise HTTPException(status_code=401, detail="Invalid internal token")

    return dependency
