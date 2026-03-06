from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import HTTPException, status
from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from ..config import settings
from ..models import Artifact, Device, InstallStatus, NewsPost, Referral, ReferralCode, Release, ReleaseApp, User, UserAppInstall
from ..schemas import MobileCheckRequest, MobileInstallReportRequest


def _utc_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _normalize_abis(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        item = str(value or "").strip().lower()
        if not item:
            continue
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def _abi_score(artifact_abis: list[str], device_abis: list[str]) -> int:
    normalized_artifact = {str(v).lower() for v in artifact_abis}
    for idx, abi in enumerate(device_abis):
        if abi in normalized_artifact:
            return 1000 - idx
    if "universal" in normalized_artifact:
        return 100
    return -1


class MobileService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def mobile_check(self, user: User, payload: MobileCheckRequest) -> dict[str, Any]:
        release = self.db.execute(select(Release).order_by(Release.published_at.desc())).scalars().first()
        if release is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No published release")

        device_abis = _normalize_abis(payload.device.abis)
        installed_map = {entry.packageName: entry for entry in payload.installedApps}
        channel = str(payload.device.appChannel or "release").strip().lower()

        self._upsert_device(user_id=user.id, payload=payload)

        rel_apps = self.db.execute(select(ReleaseApp).where(ReleaseApp.release_id == release.id)).scalars().all()
        package_candidates: dict[str, list[tuple[ReleaseApp, Artifact]]] = {}
        for rel_app in rel_apps:
            artifact = self.db.get(Artifact, rel_app.artifact_id)
            if artifact is None:
                continue
            package_name = rel_app.package_name or artifact.package_name
            if not package_name:
                continue
            if channel == "release" and package_name.endswith(".debug"):
                continue
            package_candidates.setdefault(package_name, []).append((rel_app, artifact))

        decisions = []
        for package_name, rows in package_candidates.items():
            chosen = self._select_artifact_for_device(rows, device_abis)
            if chosen is None:
                continue
            rel_app, artifact = chosen
            installed_entry = installed_map.get(package_name)
            latest_install = self._latest_successful_install(user_id=user.id, package_name=package_name)
            action, reason = self._resolve_action(
                package_name=package_name,
                release_id=release.id,
                artifact=artifact,
                installed=installed_entry,
                latest_install=latest_install,
            )
            decisions.append(
                {
                    "packageName": package_name,
                    "appName": rel_app.app_name or artifact.app_name,
                    "versionName": rel_app.version_name or artifact.version_name,
                    "versionCode": rel_app.version_code or artifact.version_code,
                    "artifactId": artifact.id,
                    "fileName": artifact.file_name,
                    "downloadUrl": f"{settings.public_base_url}/api/v1/mobile/apk/{artifact.id}",
                    "sha256": artifact.sha256,
                    "size": artifact.size,
                    "supportedAbis": artifact.supported_abis,
                    "action": action,
                    "reason": reason,
                }
            )

        return {
            "releaseId": release.id,
            "generatedAt": _utc_iso(),
            "apps": sorted(decisions, key=lambda row: (row["action"] == "UP_TO_DATE", row["packageName"])),
        }

    def _upsert_device(self, user_id: str, payload: MobileCheckRequest) -> None:
        android_hash = payload.device.androidIdHash.strip()
        if not android_hash:
            return
        row = self.db.execute(
            select(Device).where(Device.user_id == user_id, Device.android_id_hash == android_hash)
        ).scalar_one_or_none()
        if row is None:
            row = Device(
                user_id=user_id,
                android_id_hash=android_hash,
                abis=_normalize_abis(payload.device.abis),
                sdk_int=payload.device.sdkInt,
                app_channel=payload.device.appChannel,
                last_seen=datetime.now(timezone.utc),
            )
            self.db.add(row)
        else:
            row.abis = _normalize_abis(payload.device.abis)
            row.sdk_int = payload.device.sdkInt
            row.app_channel = payload.device.appChannel
            row.last_seen = datetime.now(timezone.utc)
        self.db.commit()

    def _select_artifact_for_device(self, rows: list[tuple[ReleaseApp, Artifact]], device_abis: list[str]) -> tuple[ReleaseApp, Artifact] | None:
        best = None
        best_score = -1
        for rel_app, artifact in rows:
            score = _abi_score(artifact.supported_abis or ["universal"], device_abis)
            if score > best_score:
                best = (rel_app, artifact)
                best_score = score
        return best

    def _latest_successful_install(self, user_id: str, package_name: str) -> UserAppInstall | None:
        return self.db.execute(
            select(UserAppInstall)
            .where(
                UserAppInstall.user_id == user_id,
                UserAppInstall.package_name == package_name,
                UserAppInstall.status != InstallStatus.FAILED,
            )
            .order_by(desc(UserAppInstall.installed_at))
        ).scalars().first()

    def _resolve_action(
        self,
        package_name: str,
        release_id: str,
        artifact: Artifact,
        installed,
        latest_install: UserAppInstall | None,
    ) -> tuple[str, str]:
        if installed is None:
            return "INSTALL", "not_installed"
        local_code = installed.versionCode
        remote_code = artifact.version_code
        if remote_code is not None and local_code is not None and int(remote_code) > int(local_code):
            return "UPDATE", "higher_version"
        if latest_install is not None:
            if latest_install.release_id != release_id:
                return "REINSTALL", "new_release_same_version"
            if latest_install.artifact_id and latest_install.artifact_id != artifact.id:
                return "REINSTALL", "new_artifact_same_release"
        if installed.lastInstalledReleaseId != release_id:
            return "REINSTALL", "new_release_same_version"
        if installed.lastInstalledReleaseId is None and latest_install is None:
            return "REINSTALL", "unknown_previous_release"
        return "UP_TO_DATE", "already_current"

    def install_report(self, user: User, payload: MobileInstallReportRequest) -> dict[str, Any]:
        status_map = {
            "installed": InstallStatus.INSTALLED,
            "updated": InstallStatus.UPDATED,
            "reinstalled": InstallStatus.REINSTALLED,
            "failed": InstallStatus.FAILED,
        }
        row = UserAppInstall(
            user_id=user.id,
            artifact_id=payload.artifactId,
            package_name=payload.packageName,
            release_id=payload.releaseId,
            status=status_map.get(payload.status, InstallStatus.INSTALLED),
            device_hash=payload.deviceHash,
            installed_at=datetime.now(timezone.utc),
        )
        self.db.add(row)
        self.db.commit()
        return {"ok": True}

    def get_news(self, user: User, limit: int = 50) -> list[dict]:
        rows = self.db.execute(select(NewsPost).order_by(NewsPost.published_at.desc()).limit(limit)).scalars().all()
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
            if row.visibility.value == "authenticated" or user.role.value == "admin"
        ]

    def referral_me(self, user: User) -> dict[str, Any]:
        code = self.db.execute(select(ReferralCode).where(ReferralCode.user_id == user.id)).scalar_one_or_none()
        if code is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Referral code not found")
        total = self.db.execute(
            select(func.count(Referral.id)).where(Referral.referrer_user_id == user.id)
        ).scalar_one()
        validated = self.db.execute(
            select(func.count(Referral.id)).where(Referral.referrer_user_id == user.id, Referral.validated_at.is_not(None))
        ).scalar_one()
        return {
            "referral_code": code.code,
            "referred_total": int(total or 0),
            "referred_validated": int(validated or 0),
        }

    def referral_link(self, user: User) -> dict[str, Any]:
        code = self.db.execute(select(ReferralCode).where(ReferralCode.user_id == user.id)).scalar_one_or_none()
        if code is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Referral code not found")
        return {
            "referral_code": code.code,
            "referral_link": f"{settings.public_base_url}/r/{code.code}",
        }

    def artifact_file_path(self, artifact_id: str) -> Path:
        artifact = self.db.get(Artifact, artifact_id)
        if artifact is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Artifact not found")
        file_path = Path(artifact.file_path)
        if not file_path.exists():
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Artifact file not found")
        return file_path
