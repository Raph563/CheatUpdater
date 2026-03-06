"""v2 init schema

Revision ID: 20260220_0001
Revises:
Create Date: 2026-02-20
"""

from __future__ import annotations

from alembic import op

from app.db import Base
from app import models  # noqa: F401

# revision identifiers, used by Alembic.
revision = "20260220_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    Base.metadata.create_all(bind=bind)


def downgrade() -> None:
    bind = op.get_bind()
    Base.metadata.drop_all(bind=bind)

