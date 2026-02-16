from __future__ import annotations

import hashlib
import json
import shutil
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

import requests

from .rvx_client import RvxBuilderSession, RvxError
from .state import JsonStateStore


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class PatchOrchestrator:
    def __init__(
        self,
        state_store: JsonStateStore,
        data_root: Path,
        rvx_ws_url: str,
        rvx_revanced_dir: Path,
        public_base_url: str,
    ) -> None:
        self._store = state_store
        self._data_root = data_root
        self._rvx_ws_url = rvx_ws_url
        self._rvx_revanced_dir = rvx_revanced_dir
        self._public_base_url = public_base_url.rstrip("/")

        self._job_lock = threading.Lock()
        self._running_job_id: str | None = None

        self._staged_dir = self._data_root / "artifacts" / "staged"
        self._published_dir = self._data_root / "artifacts" / "published"
        self._manifests_dir = self._data_root / "manifests"
        self._staged_dir.mkdir(parents=True, exist_ok=True)
        self._published_dir.mkdir(parents=True, exist_ok=True)
        self._manifests_dir.mkdir(parents=True, exist_ok=True)

    def get_state(self) -> dict[str, Any]:
        return self._store.load()

    def is_job_running(self) -> bool:
        with self._job_lock:
            return self._running_job_id is not None

    def refresh_catalog(self) -> dict[str, Any]:
        with RvxBuilderSession(self._rvx_ws_url, self._rvx_revanced_dir) as rvx:
            rvx.update_files()
            catalog = rvx.get_app_list()

        def mut(state: dict[str, Any]) -> dict[str, Any]:
            state["catalog"] = catalog
            return state

        return self._store.update(mut)

    def update_config(self, payload: dict[str, Any]) -> dict[str, Any]:
        def mut(state: dict[str, Any]) -> dict[str, Any]:
            config = state["config"]
            if "auto_enabled" in payload:
                config["auto_enabled"] = bool(payload["auto_enabled"])
            if "selected_apps" in payload and isinstance(payload["selected_apps"], list):
                selected_apps = []
                for item in payload["selected_apps"]:
                    if not isinstance(item, dict):
                        continue
                    package = str(item.get("package", "")).strip()
                    if not package:
                        continue
                    raw_arch = item.get("arch")
                    arch = raw_arch.strip() if isinstance(raw_arch, str) else None
                    if arch == "":
                        arch = None
                    selected_apps.append(
                        {
                            "package": package,
                            "auto": bool(item.get("auto", True)),
                            "arch": arch,
                        }
                    )
                config["selected_apps"] = selected_apps
            if "rvx_patch_source" in payload and isinstance(payload["rvx_patch_source"], dict):
                src = payload["rvx_patch_source"]
                owner = str(src.get("owner", "")).strip()
                repo = str(src.get("repo", "")).strip()
                if owner and repo:
                    config["rvx_patch_source"] = {"owner": owner, "repo": repo}
            if "github_publish" in payload and isinstance(payload["github_publish"], dict):
                pub = payload["github_publish"]
                current = config.get("github_publish", {})
                current.update(
                    {
                        "enabled": bool(pub.get("enabled", current.get("enabled", False))),
                        "owner": str(pub.get("owner", current.get("owner", ""))).strip(),
                        "repo": str(pub.get("repo", current.get("repo", ""))).strip(),
                        "token": str(pub.get("token", current.get("token", ""))).strip(),
                        "prerelease": bool(pub.get("prerelease", current.get("prerelease", False))),
                    }
                )
                config["github_publish"] = current
            return state

        return self._store.update(mut)

    def trigger_patch_job(self, trigger: str, packages: list[str] | None = None) -> str:
        with self._job_lock:
            if self._running_job_id is not None:
                raise RuntimeError("Un job est deja en cours")
            job_id = str(uuid4())
            self._running_job_id = job_id

        job = {
            "id": job_id,
            "trigger": trigger,
            "status": "running",
            "created_at": utc_now_iso(),
            "finished_at": None,
            "error": None,
            "logs": [],
            "results": [],
            "stage_id": None,
        }

        def mut(state: dict[str, Any]) -> dict[str, Any]:
            state["jobs"].insert(0, job)
            return state

        self._store.update(mut)

        thread = threading.Thread(
            target=self._run_patch_job,
            args=(job_id, packages),
            daemon=True,
        )
        thread.start()
        return job_id

    def _run_patch_job(self, job_id: str, packages: list[str] | None) -> None:
        try:
            state = self._store.load()
            config = state["config"]
            selected_apps_cfg = config.get("selected_apps", [])

            package_to_cfg = {
                str(item.get("package", "")).strip(): item
                for item in selected_apps_cfg
                if isinstance(item, dict) and str(item.get("package", "")).strip()
            }

            if packages:
                target_packages = [pkg for pkg in packages if pkg in package_to_cfg]
            else:
                target_packages = [
                    str(item.get("package", "")).strip()
                    for item in selected_apps_cfg
                    if bool(item.get("auto", True))
                ]
            if not target_packages:
                raise RuntimeError("Aucune application cible selectionnee")

            self._append_job_log(job_id, f"Job start avec {len(target_packages)} application(s)")

            with RvxBuilderSession(
                self._rvx_ws_url,
                self._rvx_revanced_dir,
                log_handler=lambda line: self._append_job_log(job_id, line),
            ) as rvx:
                rvx.update_files()
                catalog = rvx.get_app_list()

                def write_catalog(s: dict[str, Any]) -> dict[str, Any]:
                    s["catalog"] = catalog
                    return s

                self._store.update(write_catalog)

                catalog_by_package = {app["appPackage"]: app for app in catalog}
                results: list[dict[str, Any]] = []

                stage_id = f"stage-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{job_id[:8]}"
                stage_dir = self._staged_dir / stage_id
                stage_dir.mkdir(parents=True, exist_ok=True)

                for package in target_packages:
                    app = catalog_by_package.get(package)
                    if app is None:
                        results.append(
                            {
                                "package": package,
                                "status": "failed",
                                "error": "Application introuvable dans le catalogue RVX",
                            }
                        )
                        self._append_job_log(job_id, f"SKIP {package}: absent du catalogue RVX")
                        continue

                    app_cfg = package_to_cfg.get(package, {})
                    app_arch = app_cfg.get("arch")
                    self._append_job_log(job_id, f"Patch en cours: {app['appName']} ({package})")
                    try:
                        artifact = rvx.patch_app(app=app, arch=app_arch)
                        source_file = Path(artifact["file_path"])
                        copied_file = stage_dir / source_file.name
                        shutil.copy2(source_file, copied_file)

                        size = copied_file.stat().st_size
                        sha256 = self._sha256(copied_file)
                        results.append(
                            {
                                "package": package,
                                "app_name": artifact["app_name"],
                                "version": artifact["version"],
                                "status": "success",
                                "file_name": copied_file.name,
                                "stage_file_path": str(copied_file),
                                "size": size,
                                "sha256": sha256,
                            }
                        )
                        self._append_job_log(job_id, f"OK {package}: {copied_file.name}")
                    except Exception as patch_error:
                        results.append(
                            {
                                "package": package,
                                "status": "failed",
                                "error": str(patch_error),
                            }
                        )
                        self._append_job_log(job_id, f"FAIL {package}: {patch_error}")

                successful = [item for item in results if item.get("status") == "success"]
                stage_obj: dict[str, Any] | None = None
                if successful:
                    stage_obj = {
                        "id": stage_id,
                        "status": "ready",
                        "job_id": job_id,
                        "created_at": utc_now_iso(),
                        "broadcasted_at": None,
                        "apps": successful,
                        "release_id": None,
                        "github_release_url": None,
                    }

                def complete_job(state_mut: dict[str, Any]) -> dict[str, Any]:
                    for job in state_mut["jobs"]:
                        if job["id"] == job_id:
                            job["results"] = results
                            if stage_obj is not None:
                                job["stage_id"] = stage_obj["id"]
                                state_mut["stages"].insert(0, stage_obj)
                            has_fail = any(item.get("status") == "failed" for item in results)
                            has_success = any(item.get("status") == "success" for item in results)
                            if has_success and has_fail:
                                job["status"] = "partial"
                            elif has_success:
                                job["status"] = "success"
                            else:
                                job["status"] = "failed"
                            job["finished_at"] = utc_now_iso()
                            break
                    return state_mut

                self._store.update(complete_job)

        except Exception as exc:
            self._append_job_log(job_id, f"ERREUR JOB: {exc}")

            def mark_failed(state: dict[str, Any]) -> dict[str, Any]:
                for job in state["jobs"]:
                    if job["id"] == job_id:
                        job["status"] = "failed"
                        job["error"] = str(exc)
                        job["finished_at"] = utc_now_iso()
                        break
                return state

            self._store.update(mark_failed)
        finally:
            with self._job_lock:
                self._running_job_id = None

    def _append_job_log(self, job_id: str, log_line: str) -> None:
        def mut(state: dict[str, Any]) -> dict[str, Any]:
            for job in state["jobs"]:
                if job["id"] == job_id:
                    job.setdefault("logs", []).append(f"[{utc_now_iso()}] {log_line}")
                    if len(job["logs"]) > 400:
                        job["logs"] = job["logs"][-400:]
                    break
            return state

        self._store.update(mut)

    def broadcast_stage(self, stage_id: str) -> dict[str, Any]:
        state = self._store.load()
        stage = next((s for s in state["stages"] if s.get("id") == stage_id), None)
        if stage is None:
            raise KeyError("Stage introuvable")
        if stage.get("status") != "ready":
            raise RuntimeError("Stage non diffusable")

        release_id = f"rel-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
        release_dir = self._published_dir / release_id
        release_dir.mkdir(parents=True, exist_ok=True)

        apps_manifest = []
        for app_entry in stage.get("apps", []):
            source = Path(app_entry["stage_file_path"])
            target = release_dir / source.name
            shutil.copy2(source, target)
            apps_manifest.append(
                {
                    "packageName": app_entry["package"],
                    "appName": app_entry.get("app_name", app_entry["package"]),
                    "version": app_entry.get("version", "unknown"),
                    "fileName": target.name,
                    "sha256": app_entry.get("sha256"),
                    "size": app_entry.get("size"),
                    "downloadUrl": f"{self._public_base_url}/mobile/apk/{release_id}/{target.name}",
                }
            )

        manifest = {
            "releaseId": release_id,
            "tag": release_id,
            "publishedAt": utc_now_iso(),
            "apps": apps_manifest,
        }

        current_manifest = self._manifests_dir / "current.json"
        current_manifest.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

        github_release_url = None
        publish_cfg = state["config"].get("github_publish", {})
        if publish_cfg.get("enabled"):
            github_release_url = self._publish_to_github(release_id, release_dir, publish_cfg)

        def mut(state_mut: dict[str, Any]) -> dict[str, Any]:
            for s in state_mut["stages"]:
                if s.get("id") == stage_id:
                    s["status"] = "broadcasted"
                    s["broadcasted_at"] = utc_now_iso()
                    s["release_id"] = release_id
                    s["github_release_url"] = github_release_url
                    break
            state_mut["current_release"] = manifest
            return state_mut

        return self._store.update(mut)

    def cancel_stage(self, stage_id: str) -> dict[str, Any]:
        def mut(state: dict[str, Any]) -> dict[str, Any]:
            for stage in state["stages"]:
                if stage.get("id") == stage_id and stage.get("status") == "ready":
                    stage["status"] = "canceled"
                    stage["broadcasted_at"] = utc_now_iso()
                    break
            return state

        return self._store.update(mut)

    def get_mobile_current(self) -> dict[str, Any] | None:
        state = self._store.load()
        return state.get("current_release")

    def get_mobile_sources(self) -> list[dict[str, Any]]:
        return [
            {
                "id": "github_cheatupdater",
                "name": "GitHub Releases Raph563/CheatUpdater",
                "type": "github",
                "owner": "Raph563",
                "repo": "CheatUpdater",
            },
            {
                "id": "backend_main",
                "name": "CheatUpdater Backend",
                "type": "backend",
                "baseUrl": self._public_base_url,
            },
            {
                "id": "github_debug",
                "name": "GitHub Debug inotia00/rvx-builder",
                "type": "github",
                "owner": "inotia00",
                "repo": "rvx-builder",
            },
        ]

    def test_repository(self, source_id: str) -> dict[str, Any]:
        if source_id == "backend_main":
            current = self.get_mobile_current()
            return {
                "ok": True,
                "message": "Backend accessible",
                "release": current.get("releaseId") if current else None,
            }

        if source_id == "github_debug":
            resp = requests.get(
                "https://api.github.com/repos/inotia00/rvx-builder/releases/latest",
                timeout=20,
                headers={"Accept": "application/vnd.github+json"},
            )
            if resp.status_code >= 300:
                return {
                    "ok": False,
                    "message": f"GitHub HTTP {resp.status_code}",
                }
            data = resp.json()
            return {
                "ok": True,
                "message": "GitHub reachable",
                "tag": data.get("tag_name"),
            }

        if source_id == "github_cheatupdater":
            resp = requests.get(
                "https://api.github.com/repos/Raph563/CheatUpdater/releases/latest",
                timeout=20,
                headers={"Accept": "application/vnd.github+json"},
            )
            if resp.status_code == 404:
                tags_resp = requests.get(
                    "https://api.github.com/repos/Raph563/CheatUpdater/tags",
                    timeout=20,
                    headers={"Accept": "application/vnd.github+json"},
                )
                if tags_resp.status_code >= 300:
                    return {
                        "ok": False,
                        "message": f"GitHub tags HTTP {tags_resp.status_code}",
                    }
                tags = tags_resp.json()
                latest_tag = tags[0]["name"] if isinstance(tags, list) and tags else None
                return {
                    "ok": True,
                    "message": "Repo reachable (aucune release)",
                    "tag": latest_tag,
                }
            if resp.status_code >= 300:
                return {
                    "ok": False,
                    "message": f"GitHub HTTP {resp.status_code}",
                }
            data = resp.json()
            return {
                "ok": True,
                "message": "GitHub reachable",
                "tag": data.get("tag_name"),
            }

        return {
            "ok": False,
            "message": "Source inconnue",
        }

    def check_new_patch_tag(self) -> tuple[bool, str | None]:
        state = self._store.load()
        source = state["config"].get("rvx_patch_source", {})
        owner = source.get("owner")
        repo = source.get("repo")
        if not owner or not repo:
            return False, None

        latest_tag = self._fetch_latest_tag(owner, repo)
        if not latest_tag:
            return False, None

        previous = state.get("last_detected_patch_tag", "")

        def mut(s: dict[str, Any]) -> dict[str, Any]:
            s["last_detected_patch_tag"] = latest_tag
            return s

        self._store.update(mut)

        if not previous:
            return False, latest_tag
        return previous != latest_tag, latest_tag

    def _fetch_latest_tag(self, owner: str, repo: str) -> str | None:
        base = f"https://api.github.com/repos/{owner}/{repo}"
        headers = {"Accept": "application/vnd.github+json"}
        try:
            release_resp = requests.get(f"{base}/releases/latest", headers=headers, timeout=20)
            if release_resp.status_code < 300:
                release_data = release_resp.json()
                tag = str(release_data.get("tag_name", "")).strip()
                if tag:
                    return tag

            tags_resp = requests.get(f"{base}/tags", headers=headers, timeout=20)
            if tags_resp.status_code < 300:
                tags = tags_resp.json()
                if isinstance(tags, list) and tags:
                    return str(tags[0].get("name", "")).strip() or None
        except Exception:
            return None
        return None

    def _publish_to_github(
        self,
        release_id: str,
        release_dir: Path,
        publish_cfg: dict[str, Any],
    ) -> str | None:
        owner = str(publish_cfg.get("owner", "")).strip()
        repo = str(publish_cfg.get("repo", "")).strip()
        token = str(publish_cfg.get("token", "")).strip()
        prerelease = bool(publish_cfg.get("prerelease", False))

        if not owner or not repo or not token:
            return None

        api_base = f"https://api.github.com/repos/{owner}/{repo}"
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        }

        create_payload = {
            "tag_name": release_id,
            "name": f"CheatUpdater {release_id}",
            "body": "Auto-published patched APK batch from RVX backend.",
            "prerelease": prerelease,
        }

        create_resp = requests.post(f"{api_base}/releases", headers=headers, json=create_payload, timeout=30)
        if create_resp.status_code >= 300:
            raise RuntimeError(f"GitHub release creation failed: HTTP {create_resp.status_code} {create_resp.text}")

        data = create_resp.json()
        upload_url = str(data.get("upload_url", "")).split("{")[0]
        html_url = data.get("html_url")

        for apk in release_dir.glob("*.apk"):
            with apk.open("rb") as f:
                upload_resp = requests.post(
                    f"{upload_url}?name={apk.name}",
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": "application/vnd.android.package-archive",
                        "X-GitHub-Api-Version": "2022-11-28",
                    },
                    data=f,
                    timeout=120,
                )
                if upload_resp.status_code >= 300:
                    raise RuntimeError(
                        f"GitHub asset upload failed for {apk.name}: HTTP {upload_resp.status_code} {upload_resp.text}"
                    )

        return html_url

    def _sha256(self, file_path: Path) -> str:
        digest = hashlib.sha256()
        with file_path.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
