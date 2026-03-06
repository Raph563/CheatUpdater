from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw.strip())
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    data_dir: Path = Path(os.getenv("DATA_DIR", "/data"))
    state_path: Path = Path(os.getenv("STATE_PATH", "/data/state/state.json"))
    manifests_dir: Path = Path(os.getenv("MANIFESTS_DIR", "/data/manifests"))
    artifacts_dir: Path = Path(os.getenv("ARTIFACTS_DIR", "/data/artifacts"))
    rvx_ws_url: str = os.getenv("RVX_WS_URL", "ws://rvx-builder:8000")
    rvx_revanced_dir: Path = Path(os.getenv("RVX_REVANCED_DIR", "/srv/rvx/revanced"))
    public_base_url: str = os.getenv("PUBLIC_BASE_URL", "http://localhost:8088").rstrip("/")
    app_public_base_url: str = os.getenv("APP_PUBLIC_BASE_URL", "https://example.com").rstrip("/")
    database_url: str = os.getenv("DATABASE_URL", "postgresql+psycopg2://cheatupdater:cheatupdater@cheatupdater-db:5432/cheatupdater")
    jwt_secret: str = os.getenv("JWT_SECRET", "change-me-jwt-secret")
    refresh_secret: str = os.getenv("REFRESH_SECRET", "change-me-refresh-secret")
    access_token_ttl_seconds: int = _env_int("ACCESS_TOKEN_TTL_SECONDS", 900)
    refresh_token_ttl_seconds: int = _env_int("REFRESH_TOKEN_TTL_SECONDS", 60 * 60 * 24 * 30)
    allow_legacy_public: bool = _env_bool("ALLOW_LEGACY_PUBLIC", True)
    migrate_legacy_state_on_start: bool = _env_bool("MIGRATE_LEGACY_STATE_ON_START", True)
    return_debug_tokens: bool = _env_bool("RETURN_DEBUG_TOKENS", False)
    admin_seed_email: str = os.getenv("ADMIN_SEED_EMAIL", "admin@cheatupdater.local").strip().lower()
    admin_seed_password: str = os.getenv("ADMIN_SEED_PASSWORD", "ChangeThisAdminPassword!")
    smtp_host: str = os.getenv("SMTP_HOST", "").strip()
    smtp_port: int = _env_int("SMTP_PORT", 587)
    smtp_username: str = os.getenv("SMTP_USERNAME", "").strip()
    smtp_password: str = os.getenv("SMTP_PASSWORD", "").strip()
    smtp_from: str = os.getenv("SMTP_FROM", "no-reply@cheatupdater.local").strip()
    smtp_use_tls: bool = _env_bool("SMTP_USE_TLS", True)
    telegram_mode: str = os.getenv("TELEGRAM_MODE", "bot").strip().lower()
    telegram_bot_token: str = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    telegram_chat_id: str = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    telegram_poll_seconds: int = _env_int("TELEGRAM_POLL_SECONDS", 120)
    telegram_api_id: str = os.getenv("TELEGRAM_API_ID", "").strip()
    telegram_api_hash: str = os.getenv("TELEGRAM_API_HASH", "").strip()
    telegram_session_string: str = os.getenv("TELEGRAM_SESSION_STRING", "").strip()
    telegram_fallback_channel: str = os.getenv("TELEGRAM_CHANNEL", "").strip()


settings = Settings()
