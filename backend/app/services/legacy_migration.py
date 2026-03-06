from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from sqlalchemy.orm import Session

from ..config import settings
from ..models import NewsPost, NewsType, NewsVisibility, Source, SourceType
from .artifact_service import ensure_rvx_release_from_manifest, load_current_manifest_file


def migrate_legacy_state(db: Session) -> dict[str, Any]:
    report: dict[str, Any] = {
        "ok": True,
        "legacy_state_found": False,
        "running_jobs_marked_failed_stale": 0,
        "release_synced": False,
        "source_config_synced": False,
    }
    state_path = settings.state_path
    if not state_path.exists():
        return report
    report["legacy_state_found"] = True
    state_payload = _load_json_file(state_path)
    if not isinstance(state_payload, dict):
        return report

    jobs = state_payload.get("jobs")
    if isinstance(jobs, list):
        changed = False
        stale_count = 0
        for item in jobs:
            if not isinstance(item, dict):
                continue
            if str(item.get("status", "")).lower() == "running":
                item["status"] = "failed_stale"
                item["finished_at"] = datetime.now(timezone.utc).isoformat()
                item["error"] = "Marked stale during V2 migration startup"
                stale_count += 1
                changed = True
        if changed:
            state_path.write_text(json.dumps(state_payload, indent=2), encoding="utf-8")
        report["running_jobs_marked_failed_stale"] = stale_count

    cfg = state_payload.get("config", {})
    if isinstance(cfg, dict):
        report["source_config_synced"] = _sync_source_config(db, cfg)

    manifest = load_current_manifest_file()
    if manifest:
        release_id = ensure_rvx_release_from_manifest(db, manifest)
        report["release_synced"] = bool(release_id)
        if release_id:
            _ensure_changelog_news(db, release_id)
    db.commit()
    return report


def _load_json_file(path: Path) -> dict | list | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def _sync_source_config(db: Session, cfg: dict) -> bool:
    changed = False
    patch_source = cfg.get("rvx_patch_source")
    if isinstance(patch_source, dict):
        owner = str(patch_source.get("owner", "")).strip()
        repo = str(patch_source.get("repo", "")).strip()
        source = db.query(Source).filter(Source.type == SourceType.RVX, Source.name == "rvx-builder").first()
        if source is not None:
            config = dict(source.config_json or {})
            if owner:
                config["patch_owner"] = owner
            if repo:
                config["patch_repo"] = repo
            source.config_json = config
            changed = True
    return changed


def _ensure_changelog_news(db: Session, release_id: str) -> None:
    title = f"CheatUpdater release {release_id}"
    existing = db.query(NewsPost).filter(NewsPost.title == title).first()
    if existing:
        return
    db.add(
        NewsPost(
            title=title,
            body_md=f"Diffusion backend: `{release_id}`",
            type=NewsType.CHANGELOG,
            visibility=NewsVisibility.AUTHENTICATED,
        )
    )

