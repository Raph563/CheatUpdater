from __future__ import annotations

import enum
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import BIGINT, JSON, Boolean, DateTime, Enum, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class UserRole(str, enum.Enum):
    ADMIN = "admin"
    CLIENT = "client"


class TokenKind(str, enum.Enum):
    VERIFY_EMAIL = "verify_email"
    RESET_PASSWORD = "reset_password"


class NewsType(str, enum.Enum):
    NEWS = "news"
    CHANGELOG = "changelog"


class NewsVisibility(str, enum.Enum):
    AUTHENTICATED = "authenticated"
    ADMIN = "admin"


class SourceType(str, enum.Enum):
    RVX = "rvx"
    REVANCED_FEED = "revanced_feed"
    TELEGRAM_MYINSTA = "telegram_myinista"


class StageStatus(str, enum.Enum):
    READY = "ready"
    BROADCASTED = "broadcasted"
    CANCELED = "canceled"


class InstallStatus(str, enum.Enum):
    INSTALLED = "installed"
    UPDATED = "updated"
    REINSTALLED = "reinstalled"
    FAILED = "failed"


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    role: Mapped[UserRole] = mapped_column(Enum(UserRole), default=UserRole.CLIENT, nullable=False)
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)

    referral_code: Mapped["ReferralCode | None"] = relationship(back_populates="user", uselist=False)


class EmailToken(Base):
    __tablename__ = "email_tokens"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[TokenKind] = mapped_column(Enum(TokenKind), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(128), index=True, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class SessionToken(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    refresh_token_hash: Mapped[str] = mapped_column(String(128), unique=True, nullable=False)
    device_meta: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("user_id", "android_id_hash", name="uq_devices_user_android_hash"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    android_id_hash: Mapped[str] = mapped_column(String(128), index=True)
    abis: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    sdk_int: Mapped[int | None] = mapped_column(Integer, nullable=True)
    app_channel: Mapped[str | None] = mapped_column(String(32), nullable=True)
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class ReferralCode(Base):
    __tablename__ = "referral_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True)
    code: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)

    user: Mapped["User"] = relationship(back_populates="referral_code")


class Referral(Base):
    __tablename__ = "referrals"
    __table_args__ = (UniqueConstraint("referred_user_id", name="uq_referrals_referred"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    referrer_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    referred_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    referral_code: Mapped[str] = mapped_column(String(64), index=True)
    device_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    validated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class NewsPost(Base):
    __tablename__ = "news_posts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    body_md: Mapped[str] = mapped_column(Text, nullable=False)
    type: Mapped[NewsType] = mapped_column(Enum(NewsType), default=NewsType.NEWS, nullable=False)
    visibility: Mapped[NewsVisibility] = mapped_column(Enum(NewsVisibility), default=NewsVisibility.AUTHENTICATED, nullable=False)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow, nullable=False)


class Source(Base):
    __tablename__ = "sources"
    __table_args__ = (UniqueConstraint("type", "name", name="uq_sources_type_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    type: Mapped[SourceType] = mapped_column(Enum(SourceType), nullable=False)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    config_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    state_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow, nullable=False)


class Artifact(Base):
    __tablename__ = "artifacts"
    __table_args__ = (UniqueConstraint("sha256", name="uq_artifacts_sha256"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    source_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("sources.id", ondelete="SET NULL"), nullable=True, index=True)
    package_name: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    app_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    version_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    version_code: Mapped[int | None] = mapped_column(BIGINT, nullable=True, index=True)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    size: Mapped[int] = mapped_column(Integer, nullable=False)
    file_path: Mapped[str] = mapped_column(Text, nullable=False)
    file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    supported_abis: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    discovered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)


class Stage(Base):
    __tablename__ = "stages"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    status: Mapped[StageStatus] = mapped_column(Enum(StageStatus), default=StageStatus.READY, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    broadcasted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    source: Mapped[str] = mapped_column(String(64), default="unknown", nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)


class StageApp(Base):
    __tablename__ = "stage_apps"
    __table_args__ = (UniqueConstraint("stage_id", "artifact_id", name="uq_stage_apps_stage_artifact"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    stage_id: Mapped[str] = mapped_column(String(64), ForeignKey("stages.id", ondelete="CASCADE"), index=True)
    artifact_id: Mapped[str] = mapped_column(String(36), ForeignKey("artifacts.id", ondelete="CASCADE"), index=True)
    package_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    app_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    version_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    version_code: Mapped[int | None] = mapped_column(BIGINT, nullable=True)


class Release(Base):
    __tablename__ = "releases"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)


class ReleaseApp(Base):
    __tablename__ = "release_apps"
    __table_args__ = (UniqueConstraint("release_id", "artifact_id", name="uq_release_apps_release_artifact"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    release_id: Mapped[str] = mapped_column(String(64), ForeignKey("releases.id", ondelete="CASCADE"), index=True)
    artifact_id: Mapped[str] = mapped_column(String(36), ForeignKey("artifacts.id", ondelete="CASCADE"), index=True)
    package_name: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    app_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    version_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    version_code: Mapped[int | None] = mapped_column(BIGINT, nullable=True)
    download_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    size: Mapped[int | None] = mapped_column(Integer, nullable=True)


class UserAppInstall(Base):
    __tablename__ = "user_app_installs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    artifact_id: Mapped[str | None] = mapped_column(String(36), ForeignKey("artifacts.id", ondelete="SET NULL"), nullable=True, index=True)
    package_name: Mapped[str] = mapped_column(String(255), index=True)
    release_id: Mapped[str | None] = mapped_column(String(64), ForeignKey("releases.id", ondelete="SET NULL"), nullable=True, index=True)
    status: Mapped[InstallStatus] = mapped_column(Enum(InstallStatus), default=InstallStatus.INSTALLED, nullable=False)
    device_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    installed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, nullable=False)
