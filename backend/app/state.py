from __future__ import annotations

import json
import threading
from copy import deepcopy
from pathlib import Path
from typing import Any, Callable

DEFAULT_STATE: dict[str, Any] = {
    "config": {
        "auto_enabled": True,
        "selected_apps": [],
        "rvx_patch_source": {
            "owner": "inotia00",
            "repo": "revanced-patches",
        },
        "github_publish": {
            "enabled": False,
            "owner": "Raph563",
            "repo": "CheatUpdater",
            "token": "",
            "prerelease": False,
        },
    },
    "catalog": [],
    "last_detected_patch_tag": "",
    "jobs": [],
    "stages": [],
    "current_release": None,
}


class JsonStateStore:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._path.parent.mkdir(parents=True, exist_ok=True)
        if not self._path.exists():
            self._write(DEFAULT_STATE)

    def load(self) -> dict[str, Any]:
        with self._lock:
            return deepcopy(self._read())

    def save(self, state: dict[str, Any]) -> None:
        with self._lock:
            self._write(state)

    def update(self, mutator: Callable[[dict[str, Any]], dict[str, Any] | None]) -> dict[str, Any]:
        with self._lock:
            state = self._read()
            result = mutator(state)
            if result is not None:
                state = result
            self._write(state)
            return deepcopy(state)

    def _read(self) -> dict[str, Any]:
        raw = self._path.read_text(encoding="utf-8")
        data = json.loads(raw)
        return self._merge_missing(data)

    def _write(self, state: dict[str, Any]) -> None:
        self._path.write_text(json.dumps(state, indent=2), encoding="utf-8")

    def _merge_missing(self, state: dict[str, Any]) -> dict[str, Any]:
        merged = deepcopy(DEFAULT_STATE)
        merged.update(state)
        merged_config = deepcopy(DEFAULT_STATE["config"])
        merged_config.update(state.get("config", {}))
        merged["config"] = merged_config

        merged_patch_source = deepcopy(DEFAULT_STATE["config"]["rvx_patch_source"])
        merged_patch_source.update(merged_config.get("rvx_patch_source", {}))
        merged_config["rvx_patch_source"] = merged_patch_source

        merged_publish = deepcopy(DEFAULT_STATE["config"]["github_publish"])
        merged_publish.update(merged_config.get("github_publish", {}))
        merged_config["github_publish"] = merged_publish
        return merged
