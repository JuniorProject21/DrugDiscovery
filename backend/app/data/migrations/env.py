"""Alembic environment.

The server runs migrations itself at startup (app.data.db.init_db) and hands over its own connection.
The CLI path below is only for creating new migrations with `alembic revision --autogenerate`.
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine

from app.data.models import Base

config = context.config

# Objects the RDKit cartridge adds outside the ORM (see db.py); autogenerate must leave them alone.
CARTRIDGE_OBJECTS = {"mol", "mfp2", "ix_molecules_mol", "ix_molecules_mfp2"}


def include_object(obj, name, type_, reflected, compare_to):
    return name not in CARTRIDGE_OBJECTS


def run_migrations(connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=Base.metadata,
        include_object=include_object,
        render_as_batch=connection.dialect.name == "sqlite",  # SQLite can't ALTER most things in place
    )
    with context.begin_transaction():
        context.run_migrations()


connection = config.attributes.get("connection")
if connection is not None:
    run_migrations(connection)
else:
    if config.config_file_name:
        fileConfig(config.config_file_name)
    from app.config import get_settings

    engine = create_engine(get_settings().database_url)
    with engine.connect() as conn:
        run_migrations(conn)
        conn.commit()
