from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, EmailStr, Field


class AuthRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=256)
    referral_code: str = Field(min_length=3, max_length=64)
    device_hash: str = Field(min_length=8, max_length=128)


class AuthLoginRequest(BaseModel):
    email: EmailStr
    password: str
    device_meta: dict[str, Any] = Field(default_factory=dict)


class AuthRefreshRequest(BaseModel):
    refresh_token: str


class AuthVerifyEmailRequest(BaseModel):
    token: str


class AuthPasswordRequestResetRequest(BaseModel):
    email: EmailStr


class AuthPasswordResetRequest(BaseModel):
    token: str
    new_password: str = Field(min_length=8, max_length=256)


class AuthTokensResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class AuthProfileResponse(BaseModel):
    user_id: str
    email: EmailStr
    role: str
    is_verified: bool
    referral_code: str | None = None


class AuthSimpleResponse(BaseModel):
    ok: bool
    message: str
    verify_token_debug: str | None = None


class MobileInstalledApp(BaseModel):
    packageName: str
    versionCode: int | None = None
    lastInstalledReleaseId: str | None = None


class MobileDeviceInfo(BaseModel):
    androidIdHash: str
    abis: list[str] = Field(default_factory=list)
    sdkInt: int | None = None
    appChannel: str = "release"


class MobileCheckRequest(BaseModel):
    device: MobileDeviceInfo
    installedApps: list[MobileInstalledApp] = Field(default_factory=list)


class MobileAppDecision(BaseModel):
    packageName: str
    appName: str | None = None
    versionName: str | None = None
    versionCode: int | None = None
    artifactId: str
    fileName: str
    downloadUrl: str
    sha256: str | None = None
    size: int
    supportedAbis: list[str] = Field(default_factory=list)
    action: Literal["INSTALL", "UPDATE", "REINSTALL", "UP_TO_DATE"]
    reason: str


class MobileCheckResponse(BaseModel):
    releaseId: str
    generatedAt: str
    apps: list[MobileAppDecision]


class MobileInstallReportRequest(BaseModel):
    artifactId: str | None = None
    packageName: str
    releaseId: str | None = None
    status: Literal["installed", "updated", "reinstalled", "failed"] = "installed"
    deviceHash: str | None = None


class NewsItemResponse(BaseModel):
    id: int
    title: str
    body_md: str
    type: str
    visibility: str
    published_at: datetime
    updated_at: datetime


class NewsCreateRequest(BaseModel):
    title: str = Field(min_length=1, max_length=255)
    body_md: str = Field(min_length=1)
    type: Literal["news", "changelog"] = "news"
    visibility: Literal["authenticated", "admin"] = "authenticated"


class NewsPatchRequest(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=255)
    body_md: str | None = Field(default=None, min_length=1)
    type: Literal["news", "changelog"] | None = None
    visibility: Literal["authenticated", "admin"] | None = None


class ReferralMeResponse(BaseModel):
    referral_code: str
    referred_total: int
    referred_validated: int


class ReferralLinkResponse(BaseModel):
    referral_code: str
    referral_link: str


class AdminRevancedRefreshResponse(BaseModel):
    ok: bool
    imported: int
    skipped: int
    errors: list[str] = Field(default_factory=list)

