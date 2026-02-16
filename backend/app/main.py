from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .orchestrator import PatchOrchestrator
from .scheduler import PatchScheduler
from .state import JsonStateStore

DATA_DIR = Path(os.getenv("DATA_DIR", "/data"))
STATE_PATH = DATA_DIR / "state" / "state.json"
RVX_WS_URL = os.getenv("RVX_WS_URL", "ws://rvx-builder:8000")
RVX_REVANCED_DIR = Path(os.getenv("RVX_REVANCED_DIR", "/srv/rvx/revanced"))
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "http://localhost:8088")

state_store = JsonStateStore(STATE_PATH)
orchestrator = PatchOrchestrator(
    state_store=state_store,
    data_root=DATA_DIR,
    rvx_ws_url=RVX_WS_URL,
    rvx_revanced_dir=RVX_REVANCED_DIR,
    public_base_url=PUBLIC_BASE_URL,
)
scheduler = PatchScheduler(orchestrator, interval_seconds=600)

app = FastAPI(title="CheatUpdater RVX Backend", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


class ConfigPayload(BaseModel):
    auto_enabled: bool | None = None
    selected_apps: list[dict[str, Any]] | None = None
    rvx_patch_source: dict[str, Any] | None = None
    github_publish: dict[str, Any] | None = None


class TriggerPayload(BaseModel):
    packages: list[str] = Field(default_factory=list)


class DebugRepoPayload(BaseModel):
    source_id: str


@app.on_event("startup")
def on_startup() -> None:
    scheduler.start()


@app.on_event("shutdown")
def on_shutdown() -> None:
    scheduler.stop()


@app.get("/")
def home() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/health")
def api_health() -> dict[str, Any]:
    return {
        "ok": True,
        "rvx_ws_url": RVX_WS_URL,
        "running_job": orchestrator.is_job_running(),
    }


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
        return orchestrator.broadcast_stage(stage_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/api/stages/{stage_id}/cancel")
def api_cancel_stage(stage_id: str) -> dict[str, Any]:
    return orchestrator.cancel_stage(stage_id)


@app.post("/api/debug/repository")
def api_debug_repository(payload: DebugRepoPayload) -> dict[str, Any]:
    return orchestrator.test_repository(payload.source_id)


@app.get("/mobile/current")
def mobile_current() -> dict[str, Any]:
    current = orchestrator.get_mobile_current()
    if not current:
        raise HTTPException(status_code=404, detail="Aucune release diffusee")
    return current


@app.get("/mobile/sources")
def mobile_sources() -> dict[str, Any]:
    return {"sources": orchestrator.get_mobile_sources()}


@app.get("/mobile/debug/repository/{source_id}")
def mobile_debug_repository(source_id: str) -> dict[str, Any]:
    return orchestrator.test_repository(source_id)


@app.get("/mobile/apk/{release_id}/{file_name}")
def mobile_download_apk(release_id: str, file_name: str) -> FileResponse:
    file_path = DATA_DIR / "artifacts" / "published" / release_id / file_name
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="APK introuvable")
    return FileResponse(file_path)