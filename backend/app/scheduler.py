from __future__ import annotations

import threading
import time

from .orchestrator import PatchOrchestrator


class PatchScheduler:
    def __init__(self, orchestrator: PatchOrchestrator, interval_seconds: int = 600) -> None:
        self._orchestrator = orchestrator
        self._interval_seconds = interval_seconds
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                has_new, latest_tag = self._orchestrator.check_new_patch_tag()
                if has_new:
                    state = self._orchestrator.get_state()
                    if state["config"].get("auto_enabled"):
                        if not self._orchestrator.is_job_running():
                            self._orchestrator.trigger_patch_job(trigger=f"auto:{latest_tag}")
            except Exception:
                pass

            self._stop_event.wait(self._interval_seconds)