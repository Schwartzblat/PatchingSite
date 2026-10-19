"""The generic ad remover's public status: the switch and the Works-with list."""
from fastapi import APIRouter

import config
from utils import works_with

router = APIRouter(prefix="/generic")


@router.get("")
def generic_status():
    # works_with is served whatever the switch says; the store shows the card only when enabled.
    return {"enabled": config.GENERIC_PATCHING, "works_with": works_with.current()}
