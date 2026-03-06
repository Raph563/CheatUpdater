from __future__ import annotations

import hashlib
import re
import zipfile
from pathlib import Path
from typing import Any

from pyaxmlparser import APK


ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]


def compute_sha256(file_path: Path) -> str:
    digest = hashlib.sha256()
    with file_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def detect_supported_abis(file_path: Path) -> list[str]:
    found: set[str] = set()
    try:
        with zipfile.ZipFile(file_path) as archive:
            for name in archive.namelist():
                if not name.startswith("lib/"):
                    continue
                parts = name.split("/")
                if len(parts) < 3:
                    continue
                abi = parts[1].strip()
                if abi:
                    found.add(abi)
    except Exception:
        found = set()

    if not found:
        return ["universal"]
    ordered = [abi for abi in ABI_ORDER if abi in found]
    others = sorted([abi for abi in found if abi not in ABI_ORDER])
    return ordered + others


def parse_apk_metadata(file_path: Path) -> dict[str, Any]:
    package_name = None
    app_name = None
    version_name = None
    version_code = None
    try:
        apk = APK(str(file_path))
        package_name = apk.package_name or None
        app_name = apk.application or None
        version_name = apk.version_name or None
        raw_code = apk.version_code
        if raw_code is not None:
            try:
                version_code = int(str(raw_code))
            except ValueError:
                version_code = None
    except Exception:
        package_name = None

    if package_name is None or version_name is None:
        fallback = _parse_from_filename(file_path.name)
        package_name = package_name or fallback.get("package_name")
        version_name = version_name or fallback.get("version_name")
        version_code = version_code if version_code is not None else fallback.get("version_code")
        app_name = app_name or fallback.get("app_name")

    return {
        "package_name": package_name,
        "app_name": app_name,
        "version_name": version_name,
        "version_code": version_code,
        "supported_abis": detect_supported_abis(file_path),
    }


def _parse_from_filename(filename: str) -> dict[str, Any]:
    cleaned = filename.strip()
    m = re.search(r"(com\.[a-zA-Z0-9_.]+)", cleaned)
    package_name = m.group(1) if m else None

    version_name = None
    m_ver = re.search(r"v(\d+(?:\.\d+){1,4})", cleaned, re.IGNORECASE)
    if m_ver:
        version_name = m_ver.group(1)

    app_name = cleaned.rsplit(".", 1)[0]
    app_name = re.sub(r"[-_]+", " ", app_name).strip()[:120]

    version_code = None
    return {
        "package_name": package_name,
        "app_name": app_name,
        "version_name": version_name,
        "version_code": version_code,
    }

