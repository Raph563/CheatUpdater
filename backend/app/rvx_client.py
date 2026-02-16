from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Callable
from urllib.parse import unquote

from websocket import WebSocket, create_connection
from websocket._exceptions import WebSocketTimeoutException


class RvxError(RuntimeError):
    pass


class RvxBuilderSession:
    def __init__(
        self,
        ws_url: str,
        revanced_dir: Path,
        log_handler: Callable[[str], None] | None = None,
    ) -> None:
        self._ws_url = ws_url
        self._revanced_dir = revanced_dir
        self._log_handler = log_handler
        self._ws: WebSocket | None = None

    def __enter__(self) -> "RvxBuilderSession":
        self._ws = create_connection(self._ws_url, timeout=5)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._ws is not None:
            try:
                self._ws.close()
            except Exception:
                pass

    def update_files(self) -> None:
        self._send({"event": "updateFiles"})
        self._wait_for({"finished"}, timeout=3600)

    def get_app_list(self) -> list[dict[str, Any]]:
        self._send({"event": "getAppList"})
        msg = self._wait_for({"appList"}, timeout=300)
        app_list = msg.get("list", [])
        if not isinstance(app_list, list):
            raise RvxError("Reponse appList invalide")
        return app_list

    def patch_app(
        self,
        app: dict[str, Any],
        arch: str | None = None,
        selected_patches: list[str] | None = None,
        excluded_patches: list[str] | None = None,
    ) -> dict[str, Any]:
        before_files = {p.name for p in self._revanced_dir.glob("*.apk")}

        self._send(
            {
                "event": "selectApp",
                "selectedApp": {
                    "packageName": app["appPackage"],
                    "link": app["link"],
                    "appName": app["appName"],
                },
            }
        )

        self._send({"event": "getPatches", "showUniversalPatches": False})
        patch_msg = self._wait_for({"patchList"}, timeout=300)
        patch_list = patch_msg.get("patchList", [])
        if not isinstance(patch_list, list) or not patch_list:
            raise RvxError(f"Aucun patch recuperable pour {app['appPackage']}")

        selected, excluded = self._pick_patch_sets(
            patch_list,
            selected_patches=selected_patches,
            excluded_patches=excluded_patches,
        )
        self._send(
            {
                "event": "selectPatches",
                "selectedPatches": selected,
                "excludedPatches": excluded,
            }
        )

        self._send({"event": "getAppVersion", "checkVer": True, "page": 1})
        version_msg = self._wait_for({"appVersions", "askRootVersion"}, timeout=900)
        if version_msg.get("event") == "askRootVersion":
            self._send({"event": "getAppVersion", "useVer": True})
            self._wait_for({"finished"}, timeout=1200)
            chosen_version = "latest-unsupported"
        else:
            chosen_version = self._choose_version(version_msg)
            payload: dict[str, Any] = {
                "event": "selectAppVersion",
                "versionChoosen": chosen_version,
            }
            if arch:
                payload["arch"] = arch
            self._send(payload)
            self._wait_for({"finished"}, timeout=1800)

        self._send({"event": "patchApp", "ripLibs": False})
        self._wait_for({"buildFinished"}, timeout=3600)

        built_file = self._detect_output_apk(before_files)
        if built_file is None:
            raise RvxError("Build termine mais APK final introuvable")

        return {
            "file_path": str(built_file),
            "file_name": built_file.name,
            "package_name": app["appPackage"],
            "app_name": app["appName"],
            "version": chosen_version,
        }

    def _pick_patch_sets(
        self,
        patch_list: list[dict[str, Any]],
        selected_patches: list[str] | None,
        excluded_patches: list[str] | None,
    ) -> tuple[list[str], list[str]]:
        all_names = [str(item.get("name", "")).strip() for item in patch_list]
        all_names = [name for name in all_names if name]

        if selected_patches:
            selected = [name for name in selected_patches if name in all_names]
        else:
            selected = [
                str(item.get("name", "")).strip()
                for item in patch_list
                if str(item.get("name", "")).strip() and not bool(item.get("excluded", False))
            ]
        if not selected and all_names:
            selected = [all_names[0]]

        selected_set = set(selected)
        if excluded_patches:
            excluded = [name for name in excluded_patches if name in all_names and name not in selected_set]
        else:
            excluded = [name for name in all_names if name not in selected_set]

        return selected, excluded

    def _choose_version(self, app_versions_msg: dict[str, Any]) -> str:
        supported = str(app_versions_msg.get("supported", "")).strip()
        versions = app_versions_msg.get("versionList", [])

        if supported and supported not in {"C", "NOREC"}:
            return supported

        if isinstance(versions, list) and versions:
            first = versions[0]
            version = str(first.get("version", "")).strip()
            if version:
                return version

        raise RvxError("Impossible de determiner une version d'app a telecharger")

    def _detect_output_apk(self, before_names: set[str]) -> Path | None:
        candidates = []
        for apk in self._revanced_dir.glob("*.apk"):
            if apk.name.startswith("ReVanced-") and apk.name not in before_names:
                candidates.append(apk)

        if candidates:
            return max(candidates, key=lambda p: p.stat().st_mtime)

        fallback = [apk for apk in self._revanced_dir.glob("ReVanced-*.apk")]
        if fallback:
            return max(fallback, key=lambda p: p.stat().st_mtime)
        return None

    def _send(self, payload: dict[str, Any]) -> None:
        if self._ws is None:
            raise RvxError("WebSocket RVX non initialisee")
        self._ws.send(json.dumps(payload))

    def _wait_for(self, events: set[str], timeout: int) -> dict[str, Any]:
        if self._ws is None:
            raise RvxError("WebSocket RVX non initialisee")

        end_time = time.time() + timeout
        while time.time() < end_time:
            try:
                raw = self._ws.recv()
            except WebSocketTimeoutException:
                continue
            msg = json.loads(raw)
            event = msg.get("event")

            if event == "error":
                error_message = unquote(str(msg.get("error", "Erreur RVX")))
                raise RvxError(error_message)

            if event == "patchLog":
                log = str(msg.get("log", "")).strip()
                if log and self._log_handler is not None:
                    self._log_handler(log)

            if event in events:
                return msg

        raise RvxError(f"Timeout RVX en attente de {', '.join(sorted(events))}")
