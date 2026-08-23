from __future__ import annotations

import base64
import binascii
import hmac
import json
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.happ_routing import disable_public_url as happ_routing_disable_public_url
from app.happ_routing import is_enabled as happ_routing_enabled
from app.happ_routing import public_url as happ_routing_public_url
from app.happ_routing import routing_profile as build_happ_routing_profile


class ConfigError(RuntimeError):
    pass


CABINET_SECRET_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")


def decode_cabinet_secret(value: str) -> bytes:
    if not CABINET_SECRET_RE.fullmatch(value):
        raise ValueError("cabinet secret must be unpadded base64url")
    try:
        decoded = base64.urlsafe_b64decode(value + "=")
    except (ValueError, binascii.Error) as exc:
        raise ValueError("cabinet secret must be unpadded base64url") from exc
    if (
        len(decoded) != 32
        or base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=") != value
    ):
        raise ValueError("cabinet secret must decode to exactly 32 bytes")
    return decoded


@dataclass(frozen=True)
class Settings:
    bot_token: str
    remnawave_base_url: str
    remnawave_api_token: str
    remnawave_tls_verify: bool
    platega_merchant_id: str
    platega_api_key: str
    wdtt_api_token: str
    mtproto_provisioner_url: str
    mtproto_provisioner_token: str
    config_path: Path
    banner_path: Path
    data_dir: Path
    request_timeout: float
    data: dict[str, Any]
    cabinet_bridge_secret: str = ""
    cabinet_subject_secret: str = ""
    cabinet_bridge_key_id: str = "cabinet-v1"
    cabinet_hmac_clock_skew_seconds: int = 60
    cabinet_device_code_ttl_seconds: int = 300
    cabinet_grant_ttl_seconds: int = 86400
    cabinet_payment_return_url: str = ""
    cabinet_payment_failed_url: str = ""
    cabinet_payment_redirect_hosts: tuple[str, ...] = ("app.platega.io", "pay.platega.io")


def _required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ConfigError(f"{name} is required")
    return value


def _bot_env_values() -> dict[str, str]:
    token_file = os.getenv("TELEGRAM_BOT_TOKEN_FILE", "").strip()
    if token_file:
        return _read_env_file(Path(token_file))
    return {}


def _secret_value(name: str, values: dict[str, str]) -> str:
    return (os.getenv(name) or values.get(name) or "").strip()


def _cabinet_value(
    name: str,
    values: dict[str, str],
    default: str = "",
) -> str:
    raw_value = os.environ[name] if name in os.environ else values.get(name, default)
    return raw_value.strip()


def _bot_token(values: dict[str, str]) -> str:
    token = _secret_value("TELEGRAM_BOT_TOKEN", values)
    if not token:
        raise ConfigError("TELEGRAM_BOT_TOKEN is required")
    return token


def _read_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        raise ConfigError(f"env file does not exist: {path}")

    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _read_cabinet_env_file(path: Path) -> dict[str, str]:
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0
    if mode & 0o077:
        raise ConfigError("cabinet bridge env file must not be group/world accessible")
    return _read_env_file(path)


def _positive_cabinet_int(
    name: str,
    default: int,
    values: dict[str, str],
) -> int:
    try:
        value = int(_cabinet_value(name, values, str(default)))
    except ValueError:
        return default
    return value if value > 0 else default


def _cabinet_host_allowlist(
    name: str,
    default: tuple[str, ...],
    values: dict[str, str],
) -> tuple[str, ...]:
    raw = _cabinet_value(name, values)
    candidates = raw.split(",") if raw else list(default)
    normalized = tuple(
        dict.fromkeys(
            value.strip().lower().rstrip(".")
            for value in candidates
            if value.strip() and "/" not in value and ":" not in value and "@" not in value
        )
    )
    return normalized or default


def load_settings() -> Settings:
    config_path = Path(os.getenv("BOT_CONFIG_PATH", "/app/config.json"))
    banner_path = Path(os.getenv("BOT_BANNER_PATH", "/app/assets/levik-banner.png"))
    data_dir = Path(os.getenv("BOT_DATA_DIR", "/app/data"))
    api_env_path = Path(os.getenv("REMNAWAVE_API_ENV_FILE", "/run/secrets/remnawave-api.env"))
    cabinet_env_path_value = os.getenv("CABINET_BRIDGE_ENV_FILE", "").strip()

    if not config_path.exists():
        raise ConfigError(f"config file does not exist: {config_path}")

    bot_env = _bot_env_values()
    api_env = _read_env_file(api_env_path)
    cabinet_env = (
        _read_cabinet_env_file(Path(cabinet_env_path_value))
        if cabinet_env_path_value
        else {}
    )
    api_token = api_env.get("REMNAWAVE_API_TOKEN", "").strip()
    if not api_token:
        raise ConfigError("REMNAWAVE_API_TOKEN is missing in Remnawave API env file")

    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ConfigError(f"invalid JSON config: {exc}") from exc

    cabinet_bridge_secret = _cabinet_value("CABINET_BRIDGE_SECRET", cabinet_env)
    cabinet_subject_secret = _cabinet_value("CABINET_SUBJECT_SECRET", cabinet_env)
    if cabinet_bridge_secret and not cabinet_subject_secret:
        raise ConfigError("CABINET_SUBJECT_SECRET is required when cabinet bridge is enabled")
    if cabinet_bridge_secret:
        try:
            decode_cabinet_secret(cabinet_bridge_secret)
            decode_cabinet_secret(cabinet_subject_secret)
        except ValueError as exc:
            raise ConfigError(str(exc)) from exc
        if hmac.compare_digest(cabinet_bridge_secret, cabinet_subject_secret):
            raise ConfigError("cabinet bridge secrets must be distinct")
    cabinet_bridge_key_id = (
        _cabinet_value("CABINET_BRIDGE_KEY_ID", cabinet_env, "cabinet-v1")
        or "cabinet-v1"
    )
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", cabinet_bridge_key_id):
        raise ConfigError("CABINET_BRIDGE_KEY_ID is invalid")

    if happ_routing_enabled(data):
        try:
            happ_routing_public_url(data)
            happ_routing_disable_public_url(data)
            build_happ_routing_profile(data)
        except ValueError as exc:
            raise ConfigError(str(exc)) from exc

    return Settings(
        bot_token=_bot_token(bot_env),
        remnawave_base_url=os.getenv("REMNAWAVE_BASE_URL", "http://remnawave:3000").rstrip("/"),
        remnawave_api_token=api_token,
        remnawave_tls_verify=os.getenv("REMNAWAVE_TLS_VERIFY", "true").strip().lower() not in {"0", "false", "no"},
        platega_merchant_id=_secret_value("PLATEGA_MERCHANT_ID", bot_env),
        platega_api_key=_secret_value("PLATEGA_API_KEY", bot_env),
        wdtt_api_token=_secret_value("WDTT_API_TOKEN", bot_env),
        mtproto_provisioner_url=_secret_value("MTPROTO_PROVISIONER_URL", bot_env).rstrip("/"),
        mtproto_provisioner_token=_secret_value("MTPROTO_PROVISIONER_TOKEN", bot_env),
        config_path=config_path,
        banner_path=banner_path,
        data_dir=data_dir,
        request_timeout=float(os.getenv("REMNAWAVE_REQUEST_TIMEOUT", "12")),
        data=data,
        cabinet_bridge_secret=cabinet_bridge_secret,
        cabinet_subject_secret=cabinet_subject_secret,
        cabinet_bridge_key_id=cabinet_bridge_key_id,
        cabinet_hmac_clock_skew_seconds=_positive_cabinet_int(
            "CABINET_HMAC_CLOCK_SKEW_SECONDS",
            60,
            cabinet_env,
        ),
        cabinet_device_code_ttl_seconds=_positive_cabinet_int(
            "CABINET_DEVICE_CODE_TTL_SECONDS",
            300,
            cabinet_env,
        ),
        cabinet_grant_ttl_seconds=_positive_cabinet_int(
            "CABINET_GRANT_TTL_SECONDS",
            86400,
            cabinet_env,
        ),
        cabinet_payment_return_url=_cabinet_value(
            "CABINET_PAYMENT_RETURN_URL",
            cabinet_env,
        ),
        cabinet_payment_failed_url=_cabinet_value(
            "CABINET_PAYMENT_FAILED_URL",
            cabinet_env,
        ),
        cabinet_payment_redirect_hosts=_cabinet_host_allowlist(
            "CABINET_PAYMENT_REDIRECT_HOSTS",
            ("app.platega.io", "pay.platega.io"),
            cabinet_env,
        ),
    )
