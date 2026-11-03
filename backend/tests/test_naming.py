import httpx
import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import inspect, text

from app.data.db import MIGRATIONS_DIR, init_db, make_engine
from app.data.models import Base
from app.domain.chemistry import ChemistryService
from app.domain.naming import NOT_IN_PUBCHEM, CompoundNames, PubChemNamer
from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"
OLAPARIB_KEY = ChemistryService().prepare(OLAPARIB).inchikey
OLAPARIB_NAMES = CompoundNames("Olaparib", "4-[[3-[4-(cyclopropanecarbonyl)piperazine-1-carbonyl]-4-fluorophenyl]"
                               "methyl]-2H-phthalazin-1-one", 23725625)
NOVEL = "O=C(N1CCCCC1)C(C2=CC=CC=C2N3CCCC)=CC3=O"  # 16F: not in PubChem


class FakeNamer:
    def __init__(self, answers=None, available=True):
        self.answers = answers or {}
        self.available = available
        self.calls = 0

    def lookup(self, inchikey):
        self.calls += 1
        if not self.available:
            return None
        return self.answers.get(inchikey, NOT_IN_PUBCHEM)


# ---------- PubChemNamer ----------

def namer_with(handler):
    return PubChemNamer(client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_pubchem_names_are_parsed_and_cached():
    calls = []

    def handler(request):
        calls.append(request.url)
        return httpx.Response(200, json={"PropertyTable": {"Properties": [
            {"CID": 23725625, "Title": "Olaparib", "IUPACName": OLAPARIB_NAMES.iupac_name}]}})

    namer = namer_with(handler)
    assert namer.lookup(OLAPARIB_KEY) == OLAPARIB_NAMES
    assert namer.lookup(OLAPARIB_KEY) == OLAPARIB_NAMES
    assert len(calls) == 1
    assert OLAPARIB_KEY in str(calls[0])  # only the InChIKey is sent, never the SMILES


def test_pubchem_unknown_slow_or_down():
    assert namer_with(lambda r: httpx.Response(404, json={})).lookup("KEY") == NOT_IN_PUBCHEM
    assert namer_with(lambda r: httpx.Response(503)).lookup("KEY") is None

    def offline(request):
        raise httpx.ConnectError("no network")

    assert namer_with(offline).lookup("KEY") is None
    assert PubChemNamer(enabled=False).lookup("KEY") is None


def test_long_pubchem_titles_are_not_used_as_names():
    title = "N-[2-(dimethylamino)ethyl]-4-[(very-long-systematic-name)]benzamide"
    namer = namer_with(lambda r: httpx.Response(200, json={"PropertyTable": {"Properties": [
        {"CID": 1, "Title": title, "IUPACName": title}]}}))
    names = namer.lookup("KEY")
    assert names.common_name is None and names.iupac_name == title and names.found


# ---------- names in predictions ----------

@pytest.fixture
def named_client(make_client, toy_bundle):
    client = make_client(toy_bundle)
    client.app.state.container.namer = FakeNamer({OLAPARIB_KEY: OLAPARIB_NAMES})
    return client


def test_known_compounds_are_listed_by_name(named_client):
    auth = register(named_client)
    olaparib = named_client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    assert olaparib["name"] == "Olaparib"
    assert olaparib["common_name"] == "Olaparib"
    assert olaparib["iupac_name"].startswith("4-[[3-[4-(cyclopropanecarbonyl)")
    assert olaparib["pubchem_cid"] == 23725625
    assert olaparib["formula"] == "C24H23FN4O3"

    novel = named_client.post("/api/v1/predictions", json={"smiles": NOVEL}, headers=auth).json()
    assert novel["name"] == "XYZ-001"  # numbering is only used for unnamed compounds
    assert novel["common_name"] is None and novel["pubchem_cid"] is None


def test_names_are_looked_up_once_per_structure(named_client):
    namer = named_client.app.state.container.namer
    auth = register(named_client)
    named_client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth)
    named_client.post("/api/v1/predictions", json={"smiles": OLAPARIB + ".Cl"}, headers=auth)
    assert namer.calls == 1


def test_pubchem_outage_doesnt_block_predictions_and_is_retried(named_client):
    namer = named_client.app.state.container.namer
    namer.available = False
    auth = register(named_client)
    first = named_client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    assert first["name"] == "XYZ-001" and first["common_name"] is None
    namer.available = True
    second = named_client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    assert second["name"] == "Olaparib" and namer.calls == 2


def test_rename_a_prediction(named_client):
    auth = register(named_client)
    entry = named_client.post("/api/v1/predictions", json={"smiles": NOVEL}, headers=auth).json()
    renamed = named_client.patch(f"/api/v1/predictions/{entry['id']}", json={"name": " 16F "}, headers=auth).json()
    assert renamed["name"] == "16F" and renamed["is_saved"] is False
    for bad in ("", "   ", "x" * 51):
        assert named_client.patch(f"/api/v1/predictions/{entry['id']}", json={"name": bad},
                                  headers=auth).status_code == 422


def test_validate_shows_the_name(named_client):
    auth = register(named_client)
    known = named_client.post("/api/v1/chem/validate", json={"smiles": OLAPARIB}, headers=auth).json()
    assert known["common_name"] == "Olaparib" and known["pubchem_cid"] == 23725625
    novel = named_client.post("/api/v1/chem/validate", json={"smiles": NOVEL}, headers=auth).json()
    assert novel["valid"] and novel["common_name"] is None


# ---------- migrations ----------

def test_database_from_the_first_release_is_upgraded(tmp_path):
    engine = make_engine(f"sqlite:///{tmp_path / 'legacy.db'}")
    config = Config()
    config.set_main_option("script_location", str(MIGRATIONS_DIR))
    with engine.begin() as conn:  # build a first-release database: schema 0001, no migration history
        config.attributes["connection"] = conn
        command.upgrade(config, "0001")
        conn.execute(text("DROP TABLE alembic_version"))
        conn.execute(text("INSERT INTO users (email, password_hash, full_name, next_compound_number, created_at) "
                          "VALUES ('old@example.com', 'x', 'Old', 1, '2026-10-01 00:00:00')"))
    init_db(engine)
    assert "google_sub" in {c["name"] for c in inspect(engine).get_columns("users")}
    assert "password_reset_codes" in inspect(engine).get_table_names()
    with engine.connect() as conn:
        assert conn.execute(text("SELECT email FROM users")).scalar() == "old@example.com"
        assert conn.execute(text("SELECT version_num FROM alembic_version")).scalar() == "0002"


def test_models_and_migrations_agree(tmp_path):
    """Fails if app/data/models.py changed without a migration (run alembic revision --autogenerate)."""
    engine = make_engine(f"sqlite:///{tmp_path / 'fresh.db'}")
    init_db(engine)
    with engine.connect() as conn:
        assert compare_metadata(MigrationContext.configure(conn), Base.metadata) == []
