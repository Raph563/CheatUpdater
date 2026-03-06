from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

import jwt
from passlib.context import CryptContext

from .config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    return pwd_context.verify(password, password_hash)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def random_token(size: int = 48) -> str:
    return secrets.token_urlsafe(size)


def create_access_token(user_id: str, role: str, extra: dict[str, Any] | None = None) -> str:
    payload: dict[str, Any] = {
        "sub": user_id,
        "role": role,
        "typ": "access",
        "iat": int(utcnow().timestamp()),
        "exp": int((utcnow() + timedelta(seconds=settings.access_token_ttl_seconds)).timestamp()),
    }
    if extra:
        payload.update(extra)
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


def create_refresh_token(user_id: str, session_id: str, extra: dict[str, Any] | None = None) -> str:
    payload: dict[str, Any] = {
        "sub": user_id,
        "sid": session_id,
        "typ": "refresh",
        "iat": int(utcnow().timestamp()),
        "exp": int((utcnow() + timedelta(seconds=settings.refresh_token_ttl_seconds)).timestamp()),
    }
    if extra:
        payload.update(extra)
    return jwt.encode(payload, settings.refresh_secret, algorithm="HS256")


def decode_access_token(token: str) -> dict[str, Any]:
    return jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])


def decode_refresh_token(token: str) -> dict[str, Any]:
    return jwt.decode(token, settings.refresh_secret, algorithms=["HS256"])

