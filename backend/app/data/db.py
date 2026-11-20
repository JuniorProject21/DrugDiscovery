import logging
from dataclasses import dataclass
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, event, inspect, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session, sessionmaker

log = logging.getLogger(__name__)

MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"

# Added on top of the ORM schema when the RDKit cartridge is installed in PostgreSQL.
# Both columns are generated from data the application already writes, so inserts don't change.
_CARTRIDGE_DDL = [
    "CREATE EXTENSION IF NOT EXISTS rdkit",
    "ALTER TABLE molecules ADD COLUMN IF NOT EXISTS mol mol "
    "GENERATED ALWAYS AS (mol_from_smiles(canonical_smiles)) STORED",
    "ALTER TABLE molecules ADD COLUMN IF NOT EXISTS mfp2 bfp "
    "GENERATED ALWAYS AS (bfp_from_binary_text(fingerprint)) STORED",
    "CREATE INDEX IF NOT EXISTS ix_molecules_mol ON molecules USING gist (mol)",
    "CREATE INDEX IF NOT EXISTS ix_molecules_mfp2 ON molecules USING gist (mfp2)",
]


@dataclass(frozen=True)
class DatabaseFeatures:
    dialect: str
    rdkit_cartridge: bool


def make_engine(url: str) -> Engine:
    kwargs: dict = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    engine = create_engine(url, **kwargs)

    if engine.dialect.name == "sqlite":
        # Let SQLAlchemy manage transactions itself so SAVEPOINTs work (see the SQLAlchemy
        # "pysqlite serializable isolation" recipe), and enforce foreign keys.
        @event.listens_for(engine, "connect")
        def _on_connect(dbapi_connection, _record):
            dbapi_connection.isolation_level = None
            dbapi_connection.execute("PRAGMA foreign_keys=ON")

        @event.listens_for(engine, "begin")
        def _on_begin(conn):
            conn.exec_driver_sql("BEGIN")

    return engine


def make_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False)


def init_db(engine: Engine) -> DatabaseFeatures:
    """Bring the schema up to date and turn on the RDKit cartridge when PostgreSQL has it."""
    upgrade_schema(engine)
    cartridge = engine.dialect.name == "postgresql" and enable_rdkit_cartridge(engine)
    if engine.dialect.name == "sqlite":
        log.warning("Using SQLite at %s. Set INHIBITAI_DATABASE_URL to use PostgreSQL.", engine.url.database)
    return DatabaseFeatures(dialect=engine.dialect.name, rdkit_cartridge=cartridge)


def upgrade_schema(engine: Engine) -> None:
    """Apply any pending migrations (app/data/migrations). Existing data is kept."""
    logging.getLogger("alembic.runtime.plugins").setLevel(logging.WARNING)
    config = Config()
    config.set_main_option("script_location", str(MIGRATIONS_DIR))
    with engine.begin() as conn:
        config.attributes["connection"] = conn
        tables = set(inspect(conn).get_table_names())
        if "users" in tables and "alembic_version" not in tables:
            # Created by the first release, before migrations existed: it matches revision 0001.
            log.info("Existing database without migration history; marking it as revision 0001.")
            command.stamp(config, "0001")
        command.upgrade(config, "head")


def enable_rdkit_cartridge(engine: Engine) -> bool:
    try:
        with engine.begin() as conn:
            for statement in _CARTRIDGE_DDL:
                conn.execute(text(statement))
    except DBAPIError as exc:
        log.info("RDKit cartridge not available (%s); similarity search will run in Python.",
                 str(exc.orig).strip().splitlines()[0])
        return False
    log.info("RDKit cartridge enabled: molecules.mol and molecules.mfp2 are indexed.")
    return True
