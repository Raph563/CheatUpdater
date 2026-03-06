from __future__ import annotations

import threading
from datetime import datetime, timezone
from typing import Callable

from ..config import settings
from ..services.telegram_ingestion import TelegramIngestionService


def _utc_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class TelegramMyInstaWorker:
    def __init__(self, session_factory: Callable[[], object]) -> None:
        self._session_factory = session_factory
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._health = {
            "ok": True,
            "last_run_at": None,
            "last_error": "",
            "last_imported": 0,
            "mode": settings.telegram_mode,
        }

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True, name="telegram-myinista-worker")
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def health(self) -> dict:
        return dict(self._health)

    def run_once(self) -> dict:
        db = self._session_factory()
        try:
            service = TelegramIngestionService(db)
            result = service.refresh_once()
            self._health["ok"] = bool(result.get("ok", False))
            self._health["last_run_at"] = _utc_iso()
            self._health["last_imported"] = int(result.get("imported", 0))
            errors = result.get("errors") or []
            self._health["last_error"] = errors[0] if errors else ""
            return result
        except Exception as exc:
            self._health["ok"] = False
            self._health["last_run_at"] = _utc_iso()
            self._health["last_error"] = str(exc)
            return {"ok": False, "imported": 0, "skipped": 0, "errors": [str(exc)]}
        finally:
            db.close()

    def _loop(self) -> None:
        while not self._stop_event.is_set():
            self.run_once()
            self._stop_event.wait(max(30, settings.telegram_poll_seconds))

