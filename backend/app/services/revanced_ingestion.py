from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import settings
from ..models import Source, SourceType
from .artifact_service import create_stage_from_artifacts, get_or_create_artifact


def utc_id(prefix: str) -> str:
    return f"{prefix}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"


class RevancedIngestionService:
    def __init__(self, db: Session) -> None:
        self.db = db
        self.http = requests.Session()
        self.http.headers.update({"Accept": "application/vnd.github+json", "User-Agent": "CheatUpdaterBackend/2.0"})
        self.root = settings.artifacts_dir / "imported" / "revanced"
        self.root.mkdir(parents=True, exist_ok=True)

    def refresh_all(self) -> dict[str, Any]:
        feeds = self.db.execute(select(Source).where(Source.type == SourceType.REVANCED_FEED)).scalars().all()
        imported = 0
        skipped = 0
        errors: list[str] = []
        all_new_artifacts = []

        for feed in feeds:
            if not feed.enabled:
                continue
            try:
                feed_result = self._refresh_feed(feed)
                imported += feed_result["imported"]
                skipped += feed_result["skipped"]
                all_new_artifacts.extend(feed_result["artifacts"])
            except Exception as exc:
                errors.append(f"{feed.name}: {exc}")
                feed_state = dict(feed.state_json or {})
                feed_state["last_error"] = str(exc)
                feed_state["last_checked_at"] = datetime.now(timezone.utc).isoformat()
                feed.state_json = feed_state

        stage_id = None
        if all_new_artifacts:
            stage_id = utc_id("stage-revanced")
            create_stage_from_artifacts(
                self.db,
                stage_id=stage_id,
                source_name="revanced_feed",
                artifacts=all_new_artifacts,
            )
        self.db.commit()
        return {
            "ok": True,
            "imported": imported,
            "skipped": skipped,
            "errors": errors,
            "stage_id": stage_id,
        }

    def _refresh_feed(self, source: Source) -> dict[str, Any]:
        cfg = dict(source.config_json or {})
        owner = str(cfg.get("owner", "")).strip()
        repo = str(cfg.get("repo", "")).strip()
        asset_regex = str(cfg.get("asset_regex", r".*\.apk$")).strip() or r".*\.apk$"
        if not owner or not repo:
            raise RuntimeError("owner/repo missing")

        response = self.http.get(f"https://api.github.com/repos/{owner}/{repo}/releases/latest", timeout=30)
        if response.status_code >= 300:
            raise RuntimeError(f"GitHub latest release HTTP {response.status_code}")
        payload = response.json()
        tag = str(payload.get("tag_name", "")).strip()
        assets = payload.get("assets", [])
        if not isinstance(assets, list):
            assets = []
        matcher = re.compile(asset_regex, re.IGNORECASE)
        folder = self.root / source.name / (tag or "latest")
        folder.mkdir(parents=True, exist_ok=True)

        imported = 0
        skipped = 0
        new_artifacts = []
        for asset in assets:
            name = str(asset.get("name", "")).strip()
            if not name or not matcher.match(name):
                continue
            download_url = str(asset.get("browser_download_url", "")).strip()
            if not download_url:
                continue
            file_path = folder / name
            if not file_path.exists():
                with self.http.get(download_url, stream=True, timeout=120) as download:
                    if download.status_code >= 300:
                        raise RuntimeError(f"Asset download failed {name}: HTTP {download.status_code}")
                    with file_path.open("wb") as handle:
                        for chunk in download.iter_content(chunk_size=1024 * 1024):
                            if chunk:
                                handle.write(chunk)
            artifact = get_or_create_artifact(
                self.db,
                file_path=file_path,
                file_name=name,
                source=source,
                fallback_app_name=source.name,
                fallback_version=tag or None,
            )
            already_known = artifact.metadata_json.get("source_release_tag") == tag if isinstance(artifact.metadata_json, dict) else False
            meta = dict(artifact.metadata_json or {})
            meta["source_release_tag"] = tag
            meta["source_release_url"] = str(payload.get("html_url", "")).strip()
            artifact.metadata_json = meta
            if already_known:
                skipped += 1
            else:
                imported += 1
                new_artifacts.append(artifact)

        state = dict(source.state_json or {})
        state["last_checked_at"] = datetime.now(timezone.utc).isoformat()
        state["last_error"] = ""
        state["last_tag"] = tag
        source.state_json = state
        return {"imported": imported, "skipped": skipped, "artifacts": new_artifacts}
