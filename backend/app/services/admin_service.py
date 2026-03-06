from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..models import Artifact, NewsPost, NewsType, NewsVisibility, Referral, Release, Source, SourceType, Stage, StageApp, StageStatus
from ..schemas import NewsCreateRequest, NewsPatchRequest
from .artifact_service import ensure_release_from_artifacts, ensure_rvx_release_from_manifest, load_current_manifest_file


class AdminService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def state_summary(self, legacy_state: dict[str, Any], telegram_health: dict[str, Any]) -> dict[str, Any]:
        source_count = self.db.execute(select(func.count(Source.id))).scalar_one()
        artifact_count = self.db.execute(select(func.count(Artifact.id))).scalar_one()
        referral_count = self.db.execute(select(func.count(Referral.id))).scalar_one()
        latest_stage = self.db.execute(select(Stage).order_by(Stage.created_at.desc())).scalars().first()
        return {
            "ok": True,
            "legacy_state": legacy_state,
            "db": {
                "sources": int(source_count or 0),
                "artifacts": int(artifact_count or 0),
                "referrals": int(referral_count or 0),
                "latest_stage": latest_stage.id if latest_stage else None,
            },
            "health": {
                "telegram": telegram_health,
            },
        }

    def list_news(self) -> list[dict]:
        rows = self.db.execute(select(NewsPost).order_by(NewsPost.published_at.desc())).scalars().all()
        return [
            {
                "id": row.id,
                "title": row.title,
                "body_md": row.body_md,
                "type": row.type.value,
                "visibility": row.visibility.value,
                "published_at": row.published_at,
                "updated_at": row.updated_at,
            }
            for row in rows
        ]

    def create_news(self, payload: NewsCreateRequest) -> dict:
        row = NewsPost(
            title=payload.title.strip(),
            body_md=payload.body_md,
            type=NewsType(payload.type),
            visibility=NewsVisibility(payload.visibility),
            published_at=datetime.now(timezone.utc),
        )
        self.db.add(row)
        self.db.commit()
        self.db.refresh(row)
        return {
            "id": row.id,
            "title": row.title,
            "body_md": row.body_md,
            "type": row.type.value,
            "visibility": row.visibility.value,
            "published_at": row.published_at,
            "updated_at": row.updated_at,
        }

    def patch_news(self, news_id: int, payload: NewsPatchRequest) -> dict:
        row = self.db.get(NewsPost, news_id)
        if row is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="News not found")
        if payload.title is not None:
            row.title = payload.title.strip()
        if payload.body_md is not None:
            row.body_md = payload.body_md
        if payload.type is not None:
            row.type = NewsType(payload.type)
        if payload.visibility is not None:
            row.visibility = NewsVisibility(payload.visibility)
        row.updated_at = datetime.now(timezone.utc)
        self.db.commit()
        self.db.refresh(row)
        return {
            "id": row.id,
            "title": row.title,
            "body_md": row.body_md,
            "type": row.type.value,
            "visibility": row.visibility.value,
            "published_at": row.published_at,
            "updated_at": row.updated_at,
        }

    def referral_stats(self) -> dict[str, Any]:
        total = self.db.execute(select(func.count(Referral.id))).scalar_one()
        validated = self.db.execute(select(func.count(Referral.id)).where(Referral.validated_at.is_not(None))).scalar_one()
        by_source = self.db.execute(
            select(Source.type, func.count(Source.id)).group_by(Source.type)
        ).all()
        return {
            "ok": True,
            "total_referrals": int(total or 0),
            "validated_referrals": int(validated or 0),
            "source_counts": {str(k.value if hasattr(k, "value") else k): int(v or 0) for k, v in by_source},
        }

    def broadcast_db_stage(self, stage_id: str) -> dict[str, Any] | None:
        stage = self.db.get(Stage, stage_id)
        if stage is None:
            return None
        if stage.status == StageStatus.CANCELED:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Stage already canceled")

        metadata = dict(stage.metadata_json or {})
        release_id = str(metadata.get("release_id", "")).strip()
        if not release_id:
            release_id = stage_id.replace("stage-", "rel-", 1) if stage_id.startswith("stage-") else f"rel-{stage_id}"

        stage_app_rows = self.db.execute(select(StageApp).where(StageApp.stage_id == stage_id)).scalars().all()
        artifacts: list[Artifact] = []
        for row in stage_app_rows:
            artifact = self.db.get(Artifact, row.artifact_id)
            if artifact is not None:
                artifacts.append(artifact)
        if not artifacts:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Stage has no artifacts")

        ensure_release_from_artifacts(
            self.db,
            release_id=release_id,
            artifacts=artifacts,
            metadata={"source": stage.source, "stage_id": stage.id},
        )
        stage.status = StageStatus.BROADCASTED
        stage.broadcasted_at = datetime.now(timezone.utc)
        metadata["release_id"] = release_id
        metadata["broadcasted_at"] = stage.broadcasted_at.isoformat()
        stage.metadata_json = metadata
        self.db.commit()
        self.ensure_changelog_for_release(release_id)
        return {
            "ok": True,
            "stage_id": stage.id,
            "release_id": release_id,
            "status": stage.status.value,
            "app_count": len(artifacts),
            "source": stage.source,
        }

    def cancel_db_stage(self, stage_id: str) -> dict[str, Any] | None:
        stage = self.db.get(Stage, stage_id)
        if stage is None:
            return None
        if stage.status == StageStatus.BROADCASTED:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Stage already broadcasted")
        stage.status = StageStatus.CANCELED
        metadata = dict(stage.metadata_json or {})
        metadata["canceled_at"] = datetime.now(timezone.utc).isoformat()
        stage.metadata_json = metadata
        self.db.commit()
        return {
            "ok": True,
            "stage_id": stage.id,
            "status": stage.status.value,
        }

    def sync_legacy_current_release(self) -> dict[str, Any]:
        manifest = load_current_manifest_file()
        if not manifest:
            return {"ok": False, "message": "No legacy current manifest"}
        release_id = ensure_rvx_release_from_manifest(self.db, manifest)
        if release_id:
            self.ensure_changelog_for_release(release_id)
        return {"ok": True, "release_id": release_id}

    def ensure_changelog_for_release(self, release_id: str) -> None:
        title = f"Broadcast {release_id}"
        existing = self.db.execute(select(NewsPost).where(NewsPost.title == title)).scalar_one_or_none()
        if existing is not None:
            return
        self.db.add(
            NewsPost(
                title=title,
                body_md=f"Nouvelle diffusion backend: `{release_id}`",
                type=NewsType.CHANGELOG,
                visibility=NewsVisibility.AUTHENTICATED,
                published_at=datetime.now(timezone.utc),
            )
        )
        self.db.commit()
