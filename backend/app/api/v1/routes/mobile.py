from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from sqlalchemy.orm import Session

from ....config import settings
from ....db import get_db
from ....deps import get_current_user
from ....schemas import (
    MobileCheckRequest,
    MobileCheckResponse,
    MobileInstallReportRequest,
    ReferralLinkResponse,
    ReferralMeResponse,
)
from ....services.mobile_service import MobileService

router = APIRouter(prefix="/api/v1/mobile", tags=["mobile"])


@router.post("/check", response_model=MobileCheckResponse)
def mobile_check(
    payload: MobileCheckRequest,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> dict:
    return MobileService(db).mobile_check(current_user, payload)


@router.post("/install-report")
def mobile_install_report(
    payload: MobileInstallReportRequest,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> dict:
    return MobileService(db).install_report(current_user, payload)


@router.get("/news")
def mobile_news(
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> dict:
    return {"items": MobileService(db).get_news(current_user)}


@router.get("/referral/me", response_model=ReferralMeResponse)
def referral_me(
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> dict:
    return MobileService(db).referral_me(current_user)


@router.get("/referral/link", response_model=ReferralLinkResponse)
def referral_link(
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> dict:
    return MobileService(db).referral_link(current_user)


@router.get("/apk/{artifact_id}")
def mobile_download_apk(
    artifact_id: str,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_user),
) -> FileResponse:
    file_path = MobileService(db).artifact_file_path(artifact_id)
    return FileResponse(file_path)


@router.get("/landing/ref/{referral_code}")
def referral_landing(referral_code: str) -> HTMLResponse:
    deep_link = f"cheatupdater://signup?ref={referral_code}"
    web_signup = f"{settings.app_public_base_url}/signup?ref={referral_code}"
    html = f"""
    <html>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>CheatUpdater Referral</title>
        <meta http-equiv="refresh" content="0;url={deep_link}">
      </head>
      <body style="font-family: sans-serif; padding: 1rem;">
        <h2>Invitation CheatUpdater</h2>
        <p>Ouverture de l'application...</p>
        <p>Si rien ne se passe: <a href="{deep_link}">ouvrir l'app</a></p>
        <p>Ou inscription web: <a href="{web_signup}">{web_signup}</a></p>
      </body>
    </html>
    """
    return HTMLResponse(content=html)


@router.get("/health")
def mobile_health() -> dict:
    return {"ok": True}

