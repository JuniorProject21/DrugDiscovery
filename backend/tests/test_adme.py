"""ADME read off the structure: properties, drug-likeness rules and structural alerts."""

import pytest
from rdkit import Chem

from app.domain.adme import profile, profile_from_smiles
from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"
ASPIRIN = "CC(=O)Oc1ccccc1C(=O)O"
GLUCOSE = "OCC1OC(O)C(O)C(O)C1O"
# A peptide-like chain: heavy, floppy and very polar, so it should fail the oral drug rules.
BIG_AND_POLAR = "NC(=O)C(N)CC(=O)NC(CO)C(=O)NC(CC(N)=O)C(=O)NC(CO)C(=O)NC(CC(=O)O)C(=O)O"


def test_a_known_drug_passes_the_drug_likeness_rules():
    a = profile_from_smiles(OLAPARIB)
    assert a["absorption"] == "high"
    assert a["toxicity"] == "low"
    assert a["alerts"] == []
    p = a["properties"]
    assert p["molecular_weight"] == pytest.approx(434.47, abs=0.5)
    assert 2.0 < p["logp"] < 3.0
    assert p["hbd"] == 1
    lipinski = next(r for r in a["rules"] if r["name"] == "Lipinski's rule of five")
    assert lipinski["passed"] is True


def test_a_heavy_polar_compound_is_marked_as_poorly_absorbed():
    a = profile_from_smiles(BIG_AND_POLAR)
    assert a["properties"]["molecular_weight"] > 500
    assert a["absorption"] == "low"
    lipinski = next(r for r in a["rules"] if r["name"] == "Lipinski's rule of five")
    assert lipinski["passed"] is False
    assert "MW" in lipinski["detail"]


def test_structural_alerts_are_reported_with_the_fragment_that_caused_them():
    a = profile_from_smiles(ASPIRIN)
    assert a["toxicity"] in ("moderate", "high")
    assert a["alerts"], "aspirin's phenol ester is a known Brenk alert"
    assert all(set(alert) == {"catalog", "description"} for alert in a["alerts"])
    alerts_rule = next(r for r in a["rules"] if r["name"] == "Structural alerts")
    assert alerts_rule["passed"] is False


def test_a_very_polar_sugar_is_not_expected_to_reach_the_brain():
    a = profile_from_smiles(GLUCOSE)
    assert a["distribution"] == "low"
    assert "too polar" in next(r for r in a["rules"] if r["name"] == "Blood-brain barrier")["detail"]


def test_every_verdict_is_backed_by_a_rule_and_marked_rule_based():
    """The report's rule: nothing may read as a model prediction when it isn't one."""
    a = profile_from_smiles(OLAPARIB)
    assert a["method"] == "rule-based"
    assert {r["name"] for r in a["rules"]} >= {
        "Lipinski's rule of five", "Veber's rules", "Oral absorption",
        "Blood-brain barrier", "Metabolic stability", "Structural alerts",
    }
    assert all(r["detail"] for r in a["rules"])


def test_the_levels_are_the_four_the_app_draws():
    for smiles in (OLAPARIB, ASPIRIN, GLUCOSE, BIG_AND_POLAR):
        a = profile_from_smiles(smiles)
        assert a["absorption"] in ("low", "moderate", "good", "high")
        assert a["distribution"] in ("low", "moderate", "good", "high")
        assert a["metabolism"] in ("low", "moderate", "good", "high")
        # Toxicity is read the other way round, so it never uses "good".
        assert a["toxicity"] in ("low", "moderate", "high")


def test_the_same_structure_always_gives_the_same_answer():
    first = profile(Chem.MolFromSmiles(OLAPARIB))
    second = profile(Chem.MolFromSmiles(OLAPARIB))
    assert first == second


def test_a_prediction_carries_adme_through_the_api(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    body = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    adme = body["prediction"]["adme"]
    assert adme["method"] == "rule-based"
    assert adme["absorption"] == "high"
    assert adme["properties"]["qed"] > 0.5


def test_adme_is_filled_in_on_predictions_made_before_it_existed(make_client, toy_bundle):
    """Old rows are cached by (molecule, model), so they would otherwise stay empty forever."""
    client = make_client(toy_bundle)
    auth = register(client)
    first = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()

    from app.data.models import Prediction
    from sqlalchemy import update
    engine = client.app.state.container.session_factory
    with engine() as session:
        session.execute(update(Prediction).values(adme=None))
        session.commit()

    again = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    assert again["cached"] is True
    assert again["prediction"]["adme"]["absorption"] == first["prediction"]["adme"]["absorption"]


def test_an_old_history_fills_its_adme_in_when_it_is_listed(make_client, toy_bundle):
    """Opening the app is what fixes up predictions made before ADME existed."""
    client = make_client(toy_bundle)
    auth = register(client)
    client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth)
    client.post("/api/v1/predictions", json={"smiles": ASPIRIN}, headers=auth)

    from app.data.models import Prediction
    from sqlalchemy import update
    with client.app.state.container.session_factory() as session:
        session.execute(update(Prediction).values(adme=None))
        session.commit()

    history = client.get("/api/v1/predictions", headers=auth).json()["items"]
    assert all(item["prediction"]["adme"] is not None for item in history)
    assert all(item["prediction"]["adme"]["method"] == "rule-based" for item in history)
    # And opening one on its own works the same way.
    one = client.get(f"/api/v1/predictions/{history[0]['id']}", headers=auth).json()
    assert one["prediction"]["adme"]["properties"]["molecular_weight"] > 0


def test_the_placeholder_model_still_gives_real_adme(make_client):
    """ADME comes from the structure, so it is honest even when the activity result is a stub."""
    client = make_client(allow_stub=True)
    auth = register(client)
    body = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    assert body["prediction"]["model_version"].startswith("stub")
    assert body["prediction"]["adme"]["properties"]["molecular_weight"] > 400
