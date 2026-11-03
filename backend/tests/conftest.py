import os
import sys
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from sklearn.ensemble import ExtraTreesClassifier
from sqlalchemy import create_engine, text

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR / "scripts"))

from export_model_bundle import export_bundle  # noqa: E402

from app.config import Settings  # noqa: E402
from app.data.models import Base  # noqa: E402
from app.domain.chemistry import ChemistryService  # noqa: E402
from app.domain.inference.featurize import Featurizer, FeaturizerConfig  # noqa: E402
from app.main import create_app  # noqa: E402

# A toy training set for exercising the code paths. NOT a real PARP-1 model.
TOY_ACTIVES = [
    "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12",  # olaparib
    "CNCc1ccc(cc1)-c1[nH]c2cc(F)cc3C(=O)NCCc1c23",  # rucaparib
    "NC(=O)c1cccc2cn(nc12)-c1ccc(cc1)C1CCCNC1",  # niraparib
    "CC1(CCCN1)c1nc2c(cccc2[nH]1)C(N)=O",  # veliparib
    "NC(=O)c1cccc(N)c1",  # 3-aminobenzamide
    "NC(=O)c1ccccc1",  # benzamide
    "O=C1NN=Cc2ccccc12",  # phthalazinone
    "O=C1NCCc2c1cccc2",
]
TOY_INACTIVES = [
    "CC(=O)Oc1ccccc1C(=O)O",  # aspirin
    "Cn1cnc2c1c(=O)n(C)c(=O)n2C",  # caffeine
    "CC(C)Cc1ccc(cc1)C(C)C(=O)O",  # ibuprofen
    "CC(=O)Nc1ccc(O)cc1",  # paracetamol
    "OCC1OC(O)C(O)C(O)C1O",  # glucose
    "CCCCCCCCCC",
    "c1ccc2ccccc2c1",
    "CCN(CC)CCOC(=O)c1ccc(N)cc1",  # procaine
]
FEATURIZER = {"kind": "morgan", "radius": 2, "n_bits": 2048}


def _featurize(smiles_list):
    chem = ChemistryService()
    mols = [chem.standardise(chem.parse(s)) for s in smiles_list]
    return Featurizer(FeaturizerConfig.from_dict(FEATURIZER)).transform(mols), mols


@pytest.fixture(scope="session")
def toy_bundle(tmp_path_factory) -> Path:
    smiles = TOY_ACTIVES + TOY_INACTIVES
    y = np.array([1] * len(TOY_ACTIVES) + [0] * len(TOY_INACTIVES))
    x, mols = _featurize(smiles)
    model = ExtraTreesClassifier(n_estimators=50, random_state=0).fit(x, y)
    path = tmp_path_factory.mktemp("models") / "toy_bundle.joblib"
    from rdkit import Chem

    export_bundle(
        path,
        model_version="toy-test-model",
        classifiers=[model],
        threshold=0.5,
        featurizer=FEATURIZER,
        cal_scores=model.predict_proba(x)[:, 1],
        cal_labels=y,
        significance=0.2,
        train_smiles=[Chem.MolToSmiles(m) for m in mols],
        reference_smiles=smiles[:5],
        reference_features=x[:5],
        metrics={"mcc": 1.0},
    )
    return path


def _database_params():
    params = ["sqlite"]
    if os.environ.get("INHIBITAI_TEST_POSTGRES_URL"):
        params.append("postgres")
    return params


@pytest.fixture(params=_database_params())
def database_url(request, tmp_path) -> str:
    if request.param == "sqlite":
        return f"sqlite:///{tmp_path / 'test.db'}"
    url = os.environ["INHIBITAI_TEST_POSTGRES_URL"]
    engine = create_engine(url)
    Base.metadata.drop_all(engine)
    with engine.begin() as conn:
        conn.execute(text("DROP TABLE IF EXISTS submissions, predictions, molecules, password_reset_codes, users, "
                          "alembic_version CASCADE"))
    engine.dispose()
    return url


@pytest.fixture
def make_client(database_url, tmp_path):
    clients = []

    def _make(bundle: Path | None = None, *, allow_stub: bool = False, **overrides) -> TestClient:
        settings = Settings(
            _env_file=None,
            database_url=database_url,
            model_bundle_path=bundle or tmp_path / "missing.joblib",
            allow_stub_model=allow_stub,
            preload_model=False,
            jwt_secret="test-secret-that-is-at-least-32-bytes-long",
            **({"pubchem_lookup": False} | overrides),  # tests must not call PubChem
        )
        client = TestClient(create_app(settings))
        clients.append(client)
        return client

    yield _make
    for client in clients:
        client.close()


def register(client: TestClient, email="noor@example.com", password="correct-horse", name="Dr. Noor") -> dict:
    response = client.post("/api/v1/auth/register", json={"email": email, "password": password, "full_name": name})
    assert response.status_code == 201, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}
