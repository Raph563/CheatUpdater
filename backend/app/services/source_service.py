from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import settings
from ..models import Source, SourceType


DEFAULT_REVANCED_FEEDS = [
    {
        "name": "revanced_manager",
        "owner": "ReVanced",
        "repo": "revanced-manager",
        "asset_regex": r".*\.apk$",
    },
]


def ensure_default_sources(db: Session) -> None:
    _ensure_source(
        db,
        source_type=SourceType.RVX,
        name="rvx-builder",
        config_json={"kind": "rvx", "notes": "Built from rvx-builder pipeline"},
    )

    for feed in DEFAULT_REVANCED_FEEDS:
        _ensure_source(
            db,
            source_type=SourceType.REVANCED_FEED,
            name=feed["name"],
            config_json={
                "owner": feed["owner"],
                "repo": feed["repo"],
                "asset_regex": feed["asset_regex"],
            },
        )

    _ensure_source(
        db,
        source_type=SourceType.TELEGRAM_MYINSTA,
        name="myinsta_telegram",
        config_json={
            "mode": settings.telegram_mode,
            "bot_token": settings.telegram_bot_token,
            "chat_id": settings.telegram_chat_id,
            "channel": settings.telegram_fallback_channel,
            "poll_seconds": settings.telegram_poll_seconds,
        },
    )
    db.commit()


def _ensure_source(db: Session, source_type: SourceType, name: str, config_json: dict) -> None:
    row = db.execute(select(Source).where(Source.type == source_type, Source.name == name)).scalar_one_or_none()
    if row is not None:
        merged = dict(row.config_json or {})
        for key, value in config_json.items():
            if key not in merged or merged[key] in {"", None}:
                merged[key] = value
        row.config_json = merged
        return
    db.add(
        Source(
            type=source_type,
            name=name,
            enabled=True,
            config_json=config_json,
            state_json={},
        )
    )

