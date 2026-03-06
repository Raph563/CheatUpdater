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

APK_LINK_RE = re.compile(r"https?://[^\s\"']+\.apk(?:\?[^\s\"']*)?", re.IGNORECASE)


def utc_id(prefix: str) -> str:
    return f"{prefix}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"


class TelegramIngestionService:
    def __init__(self, db: Session) -> None:
        self.db = db
        self.root = settings.artifacts_dir / "imported" / "telegram_myinista"
        self.root.mkdir(parents=True, exist_ok=True)

    def refresh_once(self) -> dict[str, Any]:
        source = self.db.execute(
            select(Source).where(Source.type == SourceType.TELEGRAM_MYINSTA, Source.name == "myinsta_telegram")
        ).scalar_one_or_none()
        if source is None:
            return {"ok": False, "imported": 0, "skipped": 0, "errors": ["telegram source missing"]}
        if not source.enabled:
            return {"ok": True, "imported": 0, "skipped": 0, "errors": []}

        mode = str((source.config_json or {}).get("mode") or settings.telegram_mode).strip().lower()
        if mode != "bot":
            state = dict(source.state_json or {})
            state["last_error"] = "Telethon mode not configured yet, use bot mode or provide session implementation."
            state["last_checked_at"] = datetime.now(timezone.utc).isoformat()
            source.state_json = state
            self.db.commit()
            return {"ok": False, "imported": 0, "skipped": 0, "errors": [state["last_error"]]}

        token = str((source.config_json or {}).get("bot_token") or settings.telegram_bot_token).strip()
        if not token:
            err = "TELEGRAM_BOT_TOKEN missing"
            self._save_state_error(source, err)
            return {"ok": False, "imported": 0, "skipped": 0, "errors": [err]}

        result = self._poll_bot(source, token)
        return result

    def _poll_bot(self, source: Source, token: str) -> dict[str, Any]:
        state = dict(source.state_json or {})
        offset = int(state.get("last_update_id", 0))
        chat_id_filter = str((source.config_json or {}).get("chat_id") or settings.telegram_chat_id).strip()

        session = requests.Session()
        session.headers.update({"User-Agent": "CheatUpdaterBackend/2.0"})
        api_base = f"https://api.telegram.org/bot{token}"
        response = session.get(
            f"{api_base}/getUpdates",
            params={"offset": offset + 1 if offset > 0 else None, "timeout": 20},
            timeout=40,
        )
        if response.status_code >= 300:
            err = f"Telegram getUpdates HTTP {response.status_code}"
            self._save_state_error(source, err)
            return {"ok": False, "imported": 0, "skipped": 0, "errors": [err]}

        payload = response.json()
        if not payload.get("ok", False):
            err = f"Telegram API error: {payload}"
            self._save_state_error(source, err)
            return {"ok": False, "imported": 0, "skipped": 0, "errors": [err]}

        updates = payload.get("result", [])
        imported = 0
        skipped = 0
        errors: list[str] = []
        new_artifacts = []

        for item in updates:
            update_id = int(item.get("update_id", 0))
            if update_id > offset:
                offset = update_id
            message = item.get("message") or item.get("channel_post") or {}
            if not isinstance(message, dict):
                continue
            chat = message.get("chat", {})
            msg_chat_id = str(chat.get("id", "")).strip()
            if chat_id_filter and msg_chat_id and chat_id_filter != msg_chat_id:
                continue
            file_entry = message.get("document")
            text = str(message.get("text") or message.get("caption") or "").strip()

            try:
                if isinstance(file_entry, dict) and str(file_entry.get("file_name", "")).lower().endswith(".apk"):
                    apk_path = self._download_telegram_file(session, api_base, token, file_entry)
                    artifact = get_or_create_artifact(
                        self.db,
                        file_path=apk_path,
                        file_name=apk_path.name,
                        source=source,
                        fallback_app_name="MyInsta",
                    )
                    new_artifacts.append(artifact)
                    imported += 1
                    continue

                link_match = APK_LINK_RE.search(text)
                if link_match:
                    url = link_match.group(0)
                    apk_path = self._download_direct_apk(session, url, source)
                    artifact = get_or_create_artifact(
                        self.db,
                        file_path=apk_path,
                        file_name=apk_path.name,
                        source=source,
                        fallback_app_name="MyInsta",
                    )
                    new_artifacts.append(artifact)
                    imported += 1
                else:
                    skipped += 1
            except Exception as exc:
                errors.append(str(exc))

        stage_id = None
        if new_artifacts:
            stage_id = utc_id("stage-myinista")
            create_stage_from_artifacts(
                self.db,
                stage_id=stage_id,
                source_name="telegram_myinista",
                artifacts=new_artifacts,
            )

        state["last_update_id"] = offset
        state["last_checked_at"] = datetime.now(timezone.utc).isoformat()
        state["last_error"] = errors[0] if errors else ""
        if stage_id:
            state["last_stage_id"] = stage_id
        source.state_json = state
        self.db.commit()
        return {"ok": len(errors) == 0, "imported": imported, "skipped": skipped, "errors": errors, "stage_id": stage_id}

    def _download_telegram_file(self, session: requests.Session, api_base: str, token: str, document: dict) -> Path:
        file_id = str(document.get("file_id", "")).strip()
        if not file_id:
            raise RuntimeError("Telegram document missing file_id")
        file_name = str(document.get("file_name", "")).strip() or f"{file_id}.apk"
        file_resp = session.get(f"{api_base}/getFile", params={"file_id": file_id}, timeout=30)
        if file_resp.status_code >= 300:
            raise RuntimeError(f"Telegram getFile HTTP {file_resp.status_code}")
        file_payload = file_resp.json()
        if not file_payload.get("ok", False):
            raise RuntimeError(f"Telegram getFile API error: {file_payload}")
        file_path_remote = str((file_payload.get("result") or {}).get("file_path", "")).strip()
        if not file_path_remote:
            raise RuntimeError("Telegram file_path empty")
        url = f"https://api.telegram.org/file/bot{token}/{file_path_remote}"
        out_dir = self.root / datetime.now(timezone.utc).strftime("%Y%m%d")
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / file_name
        with session.get(url, stream=True, timeout=120) as download:
            if download.status_code >= 300:
                raise RuntimeError(f"Telegram file download HTTP {download.status_code}")
            with out_path.open("wb") as handle:
                for chunk in download.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        handle.write(chunk)
        return out_path

    def _download_direct_apk(self, session: requests.Session, url: str, source: Source) -> Path:
        name = url.split("/")[-1].split("?")[0] or f"myinsta-{datetime.now(timezone.utc).strftime('%H%M%S')}.apk"
        out_dir = self.root / datetime.now(timezone.utc).strftime("%Y%m%d")
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / name
        with session.get(url, stream=True, timeout=120) as download:
            if download.status_code >= 300:
                raise RuntimeError(f"MyInsta APK download HTTP {download.status_code}")
            with out_path.open("wb") as handle:
                for chunk in download.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        handle.write(chunk)
        return out_path

    def _save_state_error(self, source: Source, error: str) -> None:
        state = dict(source.state_json or {})
        state["last_error"] = error
        state["last_checked_at"] = datetime.now(timezone.utc).isoformat()
        source.state_json = state
        self.db.commit()
