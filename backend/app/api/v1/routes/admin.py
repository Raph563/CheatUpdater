from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from ....db import get_db
from ....deps import get_current_admin
from ....models import Source
from ....runtime import get_runtime_context
from ....schemas import AdminRevancedRefreshResponse, NewsCreateRequest, NewsPatchRequest
from ....services.admin_service import AdminService
from ....services.artifact_service import ensure_rvx_release_from_manifest, load_current_manifest_file
from ....services.revanced_ingestion import RevancedIngestionService

router = APIRouter(prefix="/api/v1/admin", tags=["admin"])


@router.get("/state")
def admin_state(db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    runtime = get_runtime_context()
    legacy_state = runtime.orchestrator.get_state()
    return AdminService(db).state_summary(legacy_state=legacy_state, telegram_health=runtime.telegram_worker.health())


@router.post("/catalog/rvx/refresh")
def admin_catalog_rvx_refresh(_admin=Depends(get_current_admin)) -> dict:
    runtime = get_runtime_context()
    try:
        return runtime.orchestrator.refresh_catalog()
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc))


@router.post("/catalog/revanced/refresh", response_model=AdminRevancedRefreshResponse)
def admin_catalog_revanced_refresh(db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    return RevancedIngestionService(db).refresh_all()


@router.post("/jobs/trigger")
def admin_jobs_trigger(payload: dict, _admin=Depends(get_current_admin)) -> dict:
    runtime = get_runtime_context()
    packages = payload.get("packages", [])
    try:
        job_id = runtime.orchestrator.trigger_patch_job(trigger="manual_v2", packages=packages or None)
        return {"ok": True, "job_id": job_id}
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc))


@router.post("/stages/{stage_id}/broadcast")
def admin_stage_broadcast(stage_id: str, db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    runtime = get_runtime_context()
    service = AdminService(db)
    try:
        result = runtime.orchestrator.broadcast_stage(stage_id)
        manifest = load_current_manifest_file()
        if manifest:
            release_id = ensure_rvx_release_from_manifest(db, manifest)
            if release_id:
                service.ensure_changelog_for_release(release_id)
        return result
    except KeyError as exc:
        fallback = service.broadcast_db_stage(stage_id)
        if fallback is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))
        return fallback
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc))


@router.post("/stages/{stage_id}/cancel")
def admin_stage_cancel(stage_id: str, db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    runtime = get_runtime_context()
    try:
        return runtime.orchestrator.cancel_stage(stage_id)
    except Exception:
        fallback = AdminService(db).cancel_db_stage(stage_id)
        if fallback is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stage not found")
        return fallback


@router.get("/news")
def admin_news_list(db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    return {"items": AdminService(db).list_news()}


@router.post("/news")
def admin_news_create(payload: NewsCreateRequest, db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    return AdminService(db).create_news(payload)


@router.patch("/news/{news_id}")
def admin_news_patch(
    news_id: int,
    payload: NewsPatchRequest,
    db: Session = Depends(get_db),
    _admin=Depends(get_current_admin),
) -> dict:
    return AdminService(db).patch_news(news_id, payload)


@router.get("/referrals/stats")
def admin_referrals_stats(db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    return AdminService(db).referral_stats()


@router.get("/health/telegram")
def admin_health_telegram(_admin=Depends(get_current_admin)) -> dict:
    return get_runtime_context().telegram_worker.health()


@router.post("/health/telegram/run-once")
def admin_health_telegram_run_once(_admin=Depends(get_current_admin)) -> dict:
    return get_runtime_context().telegram_worker.run_once()


@router.get("/health/feeds")
def admin_health_feeds(db: Session = Depends(get_db), _admin=Depends(get_current_admin)) -> dict:
    rows = db.query(Source).order_by(Source.type, Source.name).all()
    return {
        "ok": True,
        "feeds": [
            {"type": row.type.value, "name": row.name, "enabled": bool(row.enabled), "state": row.state_json or {}}
            for row in rows
        ],
    }
