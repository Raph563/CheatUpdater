from __future__ import annotations

import secrets
import string
from datetime import timedelta
from typing import Any

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import settings
from ..models import EmailToken, Referral, ReferralCode, SessionToken, TokenKind, User, UserRole
from ..schemas import (
    AuthLoginRequest,
    AuthPasswordRequestResetRequest,
    AuthPasswordResetRequest,
    AuthRefreshRequest,
    AuthRegisterRequest,
)
from ..security import (
    create_access_token,
    create_refresh_token,
    decode_refresh_token,
    hash_password,
    random_token,
    sha256_text,
    utcnow,
    verify_password,
)
from .email_service import EmailService


class AuthService:
    def __init__(self, db: Session, email_service: EmailService) -> None:
        self.db = db
        self.email = email_service

    def register(self, payload: AuthRegisterRequest) -> dict[str, Any]:
        email = payload.email.strip().lower()
        existing = self.db.execute(select(User).where(User.email == email)).scalar_one_or_none()
        if existing is not None:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered")

        referral_code = self.db.execute(
            select(ReferralCode).where(ReferralCode.code == payload.referral_code.strip())
        ).scalar_one_or_none()
        if referral_code is None or not referral_code.active:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid referral code")

        abuse_count = self.db.execute(
            select(func.count(Referral.id)).where(
                Referral.device_hash == payload.device_hash.strip(),
                Referral.validated_at.is_not(None),
            )
        ).scalar_one()
        if abuse_count and abuse_count > 0:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Referral already used on this device")

        user = User(
            email=email,
            password_hash=hash_password(payload.password),
            role=UserRole.CLIENT,
            is_verified=False,
        )
        self.db.add(user)
        self.db.flush()

        own_code = self._generate_unique_referral_code()
        self.db.add(ReferralCode(user_id=user.id, code=own_code, active=True))
        self.db.add(
            Referral(
                referrer_user_id=referral_code.user_id,
                referred_user_id=user.id,
                referral_code=referral_code.code,
                device_hash=payload.device_hash.strip(),
                validated_at=None,
            )
        )

        verify_token = random_token()
        token_row = EmailToken(
            user_id=user.id,
            kind=TokenKind.VERIFY_EMAIL,
            token_hash=sha256_text(verify_token),
            expires_at=utcnow() + timedelta(hours=24),
            used_at=None,
        )
        self.db.add(token_row)
        self.db.commit()

        verify_link = f"{settings.app_public_base_url}/verify-email?token={verify_token}"
        self.email.send(
            to_email=user.email,
            subject="CheatUpdater - Verification email",
            body=f"Valide ton compte CheatUpdater avec ce lien:\n{verify_link}\n\nToken: {verify_token}",
        )
        return {
            "ok": True,
            "message": "Account created, verify email required",
            "verify_token_debug": verify_token if settings.return_debug_tokens else None,
        }

    def verify_email(self, token: str) -> dict[str, Any]:
        token_hash = sha256_text(token.strip())
        row = self.db.execute(
            select(EmailToken).where(
                EmailToken.token_hash == token_hash,
                EmailToken.kind == TokenKind.VERIFY_EMAIL,
            )
        ).scalar_one_or_none()
        if row is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid verification token")
        if row.used_at is not None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token already used")
        if row.expires_at < utcnow():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Token expired")

        user = self.db.get(User, row.user_id)
        if user is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
        user.is_verified = True
        row.used_at = utcnow()

        referral = self.db.execute(
            select(Referral).where(Referral.referred_user_id == user.id)
        ).scalar_one_or_none()
        if referral is not None and referral.validated_at is None:
            referral.validated_at = utcnow()

        self.db.commit()
        return {"ok": True, "message": "Email verified"}

    def login(self, payload: AuthLoginRequest) -> dict[str, Any]:
        email = payload.email.strip().lower()
        user = self.db.execute(select(User).where(User.email == email)).scalar_one_or_none()
        if user is None or not verify_password(payload.password, user.password_hash):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")
        if not user.is_verified:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Email not verified")

        session_row = SessionToken(
            user_id=user.id,
            refresh_token_hash="",
            device_meta=payload.device_meta or {},
            expires_at=utcnow() + timedelta(seconds=settings.refresh_token_ttl_seconds),
            revoked_at=None,
        )
        self.db.add(session_row)
        self.db.flush()

        refresh_token = create_refresh_token(user.id, session_row.id)
        session_row.refresh_token_hash = sha256_text(refresh_token)
        access_token = create_access_token(user.id, user.role.value)
        self.db.commit()

        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "bearer",
            "expires_in": settings.access_token_ttl_seconds,
        }

    def refresh(self, payload: AuthRefreshRequest) -> dict[str, Any]:
        try:
            decoded = decode_refresh_token(payload.refresh_token.strip())
        except Exception:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token")
        if decoded.get("typ") != "refresh":
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token type")
        user_id = str(decoded.get("sub", "")).strip()
        session_id = str(decoded.get("sid", "")).strip()
        if not user_id or not session_id:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh payload")

        session_row = self.db.get(SessionToken, session_id)
        if session_row is None or session_row.user_id != user_id:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session not found")
        if session_row.revoked_at is not None or session_row.expires_at < utcnow():
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session expired")
        if session_row.refresh_token_hash != sha256_text(payload.refresh_token.strip()):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token hash")

        user = self.db.get(User, user_id)
        if user is None or not user.is_verified:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User unavailable")

        access_token = create_access_token(user.id, user.role.value)
        new_refresh = create_refresh_token(user.id, session_row.id)
        session_row.refresh_token_hash = sha256_text(new_refresh)
        session_row.expires_at = utcnow() + timedelta(seconds=settings.refresh_token_ttl_seconds)
        self.db.commit()

        return {
            "access_token": access_token,
            "refresh_token": new_refresh,
            "token_type": "bearer",
            "expires_in": settings.access_token_ttl_seconds,
        }

    def request_password_reset(self, payload: AuthPasswordRequestResetRequest) -> dict[str, Any]:
        email = payload.email.strip().lower()
        user = self.db.execute(select(User).where(User.email == email)).scalar_one_or_none()
        if user is None:
            return {"ok": True, "message": "If account exists, reset link has been sent"}
        token = random_token()
        row = EmailToken(
            user_id=user.id,
            kind=TokenKind.RESET_PASSWORD,
            token_hash=sha256_text(token),
            expires_at=utcnow() + timedelta(hours=2),
            used_at=None,
        )
        self.db.add(row)
        self.db.commit()
        reset_link = f"{settings.app_public_base_url}/reset-password?token={token}"
        self.email.send(
            to_email=user.email,
            subject="CheatUpdater - Password reset",
            body=f"Reset ton mot de passe:\n{reset_link}\n\nToken: {token}",
        )
        return {"ok": True, "message": "If account exists, reset link has been sent", "verify_token_debug": token if settings.return_debug_tokens else None}

    def reset_password(self, payload: AuthPasswordResetRequest) -> dict[str, Any]:
        token_hash = sha256_text(payload.token.strip())
        row = self.db.execute(
            select(EmailToken).where(
                EmailToken.token_hash == token_hash,
                EmailToken.kind == TokenKind.RESET_PASSWORD,
            )
        ).scalar_one_or_none()
        if row is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid reset token")
        if row.used_at is not None or row.expires_at < utcnow():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Reset token expired")

        user = self.db.get(User, row.user_id)
        if user is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

        user.password_hash = hash_password(payload.new_password)
        row.used_at = utcnow()
        self.db.commit()
        return {"ok": True, "message": "Password updated"}

    def ensure_admin_seed(self) -> None:
        existing = self.db.execute(select(User).where(User.email == settings.admin_seed_email)).scalar_one_or_none()
        if existing is not None:
            if existing.role != UserRole.ADMIN:
                existing.role = UserRole.ADMIN
                self.db.commit()
            return
        user = User(
            email=settings.admin_seed_email,
            password_hash=hash_password(settings.admin_seed_password),
            role=UserRole.ADMIN,
            is_verified=True,
        )
        self.db.add(user)
        self.db.flush()
        self.db.add(ReferralCode(user_id=user.id, code=self._generate_unique_referral_code(), active=True))
        self.db.commit()

    def _generate_unique_referral_code(self) -> str:
        alphabet = string.ascii_uppercase + string.digits
        for _ in range(50):
            code = "".join(secrets.choice(alphabet) for _ in range(8))
            exists = self.db.execute(select(ReferralCode).where(ReferralCode.code == code)).scalar_one_or_none()
            if exists is None:
                return code
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Unable to allocate referral code")

