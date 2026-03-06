from __future__ import annotations

from app.db import Base, SessionLocal, engine
from app.services.legacy_migration import migrate_legacy_state
from app.services.source_service import ensure_default_sources


def main() -> None:
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        ensure_default_sources(db)
        report = migrate_legacy_state(db)
        print(report)


if __name__ == "__main__":
    main()

