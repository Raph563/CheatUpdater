from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .api.v1.router import api_v1_router
from .config import settings
from .db import Base, SessionLocal, engine
from .orchestrator import PatchOrchestrator
from .runtime import RuntimeContext, set_runtime_context
from .scheduler import PatchScheduler
from .services.auth_service import AuthService
from .services.email_service import EmailService
from .services.legacy_migration import migrate_legacy_state
from .services.source_service import ensure_default_sources
from .services.artifact_service import ensure_rvx_release_from_manifest, load_current_manifest_file
from .services.admin_service import AdminService
from .state import JsonStateStore
from .workers.telegram_worker import TelegramMyInstaWorker

DATA_DIR = settings.data_dir
STATE_PATH = settings.state_path
RVX_WS_URL = settings.rvx_ws_url
RVX_REVANCED_DIR = settings.rvx_revanced_dir
PUBLIC_BASE_URL = settings.public_base_url

state_store = JsonStateStore(STATE_PATH)
orchestrator = PatchOrchestrator(
    state_store=state_store,
    data_root=DATA_DIR,
    rvx_ws_url=RVX_WS_URL,
    rvx_revanced_dir=RVX_REVANCED_DIR,
    public_base_url=PUBLIC_BASE_URL,
)
scheduler = PatchScheduler(orchestrator, interval_seconds=600)
telegram_worker = TelegramMyInstaWorker(session_factory=SessionLocal)

app = FastAPI(title="CheatUpdater Backend V2", version="2.0.0")
app.include_router(api_v1_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

set_runtime_context(RuntimeContext(orchestrator=orchestrator, scheduler=scheduler, telegram_worker=telegram_worker))


class ConfigPayload(BaseModel):
    auto_enabled: bool | None = None
    selected_apps: list[dict[str, Any]] | None = None
    rvx_patch_source: dict[str, Any] | None = None
    github_publish: dict[str, Any] | None = None


class TriggerPayload(BaseModel):
    packages: list[str] = Field(default_factory=list)


class DebugRepoPayload(BaseModel):
    source_id: str


def _legacy_guard() -> None:
    if not settings.allow_legacy_public:
        raise HTTPException(status_code=404, detail="Legacy public endpoints are disabled")


@app.on_event("startup")
def on_startup() -> None:
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        ensure_default_sources(db)
        AuthService(db=db, email_service=EmailService()).ensure_admin_seed()
        if settings.migrate_legacy_state_on_start:
            migrate_legacy_state(db)
    scheduler.start()
    telegram_worker.start()


@app.on_event("shutdown")
def on_shutdown() -> None:
    scheduler.stop()
    telegram_worker.stop()


@app.get("/")
def home() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/r/{referral_code}")
def referral_short_link(referral_code: str) -> RedirectResponse:
    return RedirectResponse(url=f"/api/v1/mobile/landing/ref/{referral_code}", status_code=307)


@app.get("/api/health")
def api_health() -> dict[str, Any]:
    return {
        "ok": True,
        "version": "2.0.0",
        "rvx_ws_url": RVX_WS_URL,
        "running_job": orchestrator.is_job_running(),
        "telegram": telegram_worker.health(),
        "legacy_public_enabled": settings.allow_legacy_public,
    }


# Legacy admin endpoints (kept for migration UI)
@app.get("/api/state")
def api_state() -> dict[str, Any]:
    return orchestrator.get_state()


@app.post("/api/catalog/refresh")
def api_refresh_catalog() -> dict[str, Any]:
    try:
        return orchestrator.refresh_catalog()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/api/config")
def api_config(payload: ConfigPayload) -> dict[str, Any]:
    return orchestrator.update_config(payload.model_dump(exclude_none=True))


@app.post("/api/jobs/trigger")
def api_trigger_job(payload: TriggerPayload) -> dict[str, Any]:
    try:
        job_id = orchestrator.trigger_patch_job(
            trigger="manual",
            packages=payload.packages or None,
        )
        return {"ok": True, "job_id": job_id}
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/api/stages/{stage_id}/broadcast")
def api_broadcast_stage(stage_id: str) -> dict[str, Any]:
    try:
        result = orchestrator.broadcast_stage(stage_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
    with SessionLocal() as db:
        manifest = load_current_manifest_file()
        if manifest:
            release_id = ensure_rvx_release_from_manifest(db, manifest)
            if release_id:
                AdminService(db).ensure_changelog_for_release(release_id)
    return result


@app.post("/api/stages/{stage_id}/cancel")
def api_cancel_stage(stage_id: str) -> dict[str, Any]:
    return orchestrator.cancel_stage(stage_id)


@app.post("/api/debug/repository")
def api_debug_repository(payload: DebugRepoPayload) -> dict[str, Any]:
    return orchestrator.test_repository(payload.source_id)


# Legacy mobile endpoints (disabled when ALLOW_LEGACY_PUBLIC=false)
@app.get("/mobile/current")
def mobile_current() -> dict[str, Any]:
    _legacy_guard()
    current = orchestrator.get_mobile_current()
    if not current:
        raise HTTPException(status_code=404, detail="Aucune release diffusee")
    return current


@app.get("/mobile/sources")
def mobile_sources() -> dict[str, Any]:
    _legacy_guard()
    return {"sources": orchestrator.get_mobile_sources()}


@app.get("/mobile/debug/repository/{source_id}")
def mobile_debug_repository(source_id: str) -> dict[str, Any]:
    _legacy_guard()
    return orchestrator.test_repository(source_id)


@app.get("/mobile/apk/{release_id}/{file_name}")
def mobile_download_apk(release_id: str, file_name: str) -> FileResponse:
    _legacy_guard()
    file_path = DATA_DIR / "artifacts" / "published" / release_id / file_name
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="APK introuvable")
    return FileResponse(file_path)
