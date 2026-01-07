from pathlib import Path
import dataclasses

@dataclasses.dataclass
class AppConfig:
    name: str
    package: str

APPS = [
    AppConfig(name="WhatsApp", package="com.whatsapp"),
    AppConfig(name="Moovit", package="com.tranzmate"),
    AppConfig(name="12+", package="com.keshet.mako.VOD"),
]

PATCHED_APPS_PATH = Path("/opt/patched")