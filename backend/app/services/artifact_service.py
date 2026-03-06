from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

from sqlalchemy import desc, select
from sqlalchemy.orm import Session

from ..config import settings
from ..models import Artifact, Release, ReleaseApp, Source, SourceType, Stage, StageApp, StageStatus
from ..utils.apk_meta import compute_sha256, parse_apk_metadata


def get_or_create_artifact(
    db: Session,
    file_path: Path,
    file_name: str,
    source: Source | None,
    fallback_package: str | None = None,
    fallback_app_name: str | None = None,
    fallback_version: str | None = None,
) -> Artifact:
    if not file_path.exists():
        raise FileNotFoundError(str(file_path))
    sha = compute_sha256(file_path)
    existing = db.execute(select(Artifact).where(Artifact.sha256 == sha)).scalar_one_or_none()
    if existing is not None:
        return existing

    parsed = parse_apk_metadata(file_path)
    artifact = Artifact(
        source_id=source.id if source else None,
        package_name=parsed.get("package_name") or fallback_package,
        app_name=parsed.get("app_name") or fallback_app_name,
        version_name=parsed.get("version_name") or fallback_version,
        version_code=parsed.get("version_code"),
        sha256=sha,
        size=file_path.stat().st_size,
        file_path=str(file_path),
        file_name=file_name,
        supported_abis=parsed.get("supported_abis") or ["universal"],
        metadata_json=parsed,
    )
    db.add(artifact)
    db.flush()
    return artifact


def ensure_release_from_artifacts(
    db: Session,
    release_id: str,
    artifacts: Iterable[Artifact],
    metadata: dict | None = None,
) -> Release:
    release = db.get(Release, release_id)
    if release is None:
        release = Release(id=release_id, metadata_json=metadata or {})
        db.add(release)
        db.flush()

    seen_artifact_ids = {
        row.artifact_id
        for row in db.execute(select(ReleaseApp).where(ReleaseApp.release_id == release_id)).scalars().all()
    }
    for artifact in artifacts:
        if artifact.id in seen_artifact_ids:
            continue
        db.add(
            ReleaseApp(
                release_id=release_id,
                artifact_id=artifact.id,
                package_name=artifact.package_name,
                app_name=artifact.app_name,
                version_name=artifact.version_name,
                version_code=artifact.version_code,
                download_url=f"{settings.public_base_url}/api/v1/mobile/apk/{artifact.id}",
                sha256=artifact.sha256,
                size=artifact.size,
            )
        )
    db.flush()
    return release


def create_stage_from_artifacts(
    db: Session,
    stage_id: str,
    source_name: str,
    artifacts: Iterable[Artifact],
    status: StageStatus = StageStatus.READY,
    metadata: dict | None = None,
) -> Stage:
    stage = db.get(Stage, stage_id)
    if stage is None:
        stage = Stage(id=stage_id, status=status, source=source_name, metadata_json=metadata or {})
        db.add(stage)
        db.flush()
    existing = {
        row.artifact_id
        for row in db.execute(select(StageApp).where(StageApp.stage_id == stage_id)).scalars().all()
    }
    for artifact in artifacts:
        if artifact.id in existing:
            continue
        db.add(
            StageApp(
                stage_id=stage.id,
                artifact_id=artifact.id,
                package_name=artifact.package_name,
                app_name=artifact.app_name,
                version_name=artifact.version_name,
                version_code=artifact.version_code,
            )
        )
    db.flush()
    return stage


def latest_release(db: Session) -> Release | None:
    return db.execute(select(Release).order_by(desc(Release.published_at))).scalars().first()


def release_apps(db: Session, release_id: str) -> list[ReleaseApp]:
    return db.execute(select(ReleaseApp).where(ReleaseApp.release_id == release_id)).scalars().all()


def ensure_rvx_release_from_manifest(db: Session, manifest: dict) -> str | None:
    release_id = str(manifest.get("releaseId", "")).strip()
    if not release_id:
        return None
    source = db.execute(
        select(Source).where(Source.type == SourceType.RVX, Source.name == "rvx-builder")
    ).scalar_one_or_none()
    artifacts: list[Artifact] = []
    for app in manifest.get("apps", []):
        file_name = str(app.get("fileName", "")).strip()
        if not file_name:
            continue
        file_path = settings.artifacts_dir / "published" / release_id / file_name
        if not file_path.exists():
            continue
        artifact = get_or_create_artifact(
            db,
            file_path=file_path,
            file_name=file_name,
            source=source,
            fallback_package=str(app.get("packageName", "")).strip() or None,
            fallback_app_name=str(app.get("appName", "")).strip() or None,
            fallback_version=str(app.get("version", "")).strip() or None,
        )
        artifacts.append(artifact)
    ensure_release_from_artifacts(db, release_id, artifacts, metadata={"source": "rvx_legacy_manifest"})
    db.commit()
    return release_id


def load_current_manifest_file() -> dict | None:
    manifest_path = settings.manifests_dir / "current.json"
    if not manifest_path.exists():
        return None
    try:
        return json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return None

