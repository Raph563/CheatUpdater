from __future__ import annotations

from fastapi import APIRouter

from .routes import admin, auth, mobile

api_v1_router = APIRouter()
api_v1_router.include_router(auth.router)
api_v1_router.include_router(mobile.router)
api_v1_router.include_router(admin.router)

