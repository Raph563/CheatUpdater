from __future__ import annotations

from dataclasses import dataclass

from .orchestrator import PatchOrchestrator
from .scheduler import PatchScheduler
from .workers.telegram_worker import TelegramMyInstaWorker


@dataclass
class RuntimeContext:
    orchestrator: PatchOrchestrator
    scheduler: PatchScheduler
    telegram_worker: TelegramMyInstaWorker


runtime_context: RuntimeContext | None = None


def set_runtime_context(ctx: RuntimeContext) -> None:
    global runtime_context
    runtime_context = ctx


def get_runtime_context() -> RuntimeContext:
    if runtime_context is None:
        raise RuntimeError("Runtime context not initialized")
    return runtime_context

