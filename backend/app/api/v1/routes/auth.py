from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ....db import get_db
from ....deps import get_current_user
from ....schemas import (
    AuthLoginRequest,
    AuthPasswordRequestResetRequest,
    AuthPasswordResetRequest,
    AuthProfileResponse,
    AuthRefreshRequest,
    AuthRegisterRequest,
    AuthSimpleResponse,
    AuthTokensResponse,
    AuthVerifyEmailRequest,
)
from ....services.auth_service import AuthService
from ....services.email_service import EmailService

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _service(db: Session) -> AuthService:
    return AuthService(db=db, email_service=EmailService())


@router.post("/register", response_model=AuthSimpleResponse)
def register(payload: AuthRegisterRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).register(payload)


@router.post("/verify-email", response_model=AuthSimpleResponse)
def verify_email(payload: AuthVerifyEmailRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).verify_email(payload.token)


@router.post("/login", response_model=AuthTokensResponse)
def login(payload: AuthLoginRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).login(payload)


@router.post("/refresh", response_model=AuthTokensResponse)
def refresh(payload: AuthRefreshRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).refresh(payload)


@router.post("/password/request-reset", response_model=AuthSimpleResponse)
def password_request_reset(payload: AuthPasswordRequestResetRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).request_password_reset(payload)


@router.post("/password/reset", response_model=AuthSimpleResponse)
def password_reset(payload: AuthPasswordResetRequest, db: Session = Depends(get_db)) -> dict:
    return _service(db).reset_password(payload)


@router.get("/me", response_model=AuthProfileResponse)
def me(current_user=Depends(get_current_user)) -> dict:
    code = current_user.referral_code.code if getattr(current_user, "referral_code", None) else None
    return {
        "user_id": current_user.id,
        "email": current_user.email,
        "role": current_user.role.value,
        "is_verified": current_user.is_verified,
        "referral_code": code,
    }

