"""Why this result: which fragments moved the score (report section 5.2, "Interpretability")."""

import pytest
from rdkit import Chem

from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"
BENZAMIDE = "NC(=O)c1ccccc1"


@pytest.fixture
def client(make_client, toy_bundle):
    return make_client(toy_bundle)


def explain(client, auth, smiles=OLAPARIB):
    return client.post("/api/v1/predictions/explain", json={"smiles": smiles}, headers=auth)


def test_an_explanation_names_fragments_and_what_each_one_was_worth(client):
    auth = register(client)
    response = explain(client, auth)
    assert response.status_code == 200, response.text
    body = response.json()

    assert body["model_version"] == "toy-test-model"
    assert body["label"] in ("active", "inactive", "uncertain")
    assert 0.0 <= body["score"] <= 1.0
    fragments = body["fragments"]
    assert fragments, "a drug-like molecule should set bits that matter to the model"
    # Strongest first, so the panel can show the top few.
    weights = [abs(f["weight"]) for f in fragments]
    assert weights == sorted(weights, reverse=True)
    for f in fragments:
        assert f["fragment"]
        assert f["occurrences"] >= 1
        assert 0 <= f["radius"] <= 2  # the toy bundle's Morgan radius
        assert f["atoms"]


def test_the_score_it_explains_is_the_score_the_prediction_gave(client):
    """Otherwise the panel would explain a different number from the one on the screen."""
    auth = register(client)
    predicted = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth).json()
    explained = explain(client, auth).json()
    assert explained["score"] == pytest.approx(predicted["prediction"]["score"])
    assert explained["threshold"] == pytest.approx(predicted["prediction"]["threshold"])
    assert explained["label"] == predicted["prediction"]["label"]
    assert explained["canonical_smiles"] == predicted["canonical_smiles"]


def test_the_atoms_it_points_at_are_atoms_of_the_structure_it_returns(client):
    """The app draws `canonical_smiles` and shades these indices, so they must index that string."""
    auth = register(client)
    body = explain(client, auth).json()
    mol = Chem.MolFromSmiles(body["canonical_smiles"])
    assert mol is not None
    for f in body["fragments"]:
        assert all(0 <= a < mol.GetNumAtoms() for a in f["atoms"])
        # A radius-r environment reaches at most a handful of atoms.
        assert len(f["atoms"]) <= mol.GetNumAtoms()


def test_the_highlighted_atoms_can_be_drawn(client):
    """The round trip the panel actually makes: explain, then ask for the shaded structure."""
    auth = register(client)
    body = explain(client, auth).json()
    atoms = body["fragments"][0]["atoms"]
    drawing = client.post("/api/v1/chem/depict", headers=auth, json={
        "smiles": body["canonical_smiles"], "width": 300, "height": 300, "theme": "light",
        "format": "svg", "highlight_atoms": atoms,
    })
    assert drawing.status_code == 200, drawing.text
    assert drawing.headers["content-type"].startswith("image/svg")
    assert b"<svg" in drawing.content


def test_highlighting_an_atom_that_is_not_there_is_refused(client):
    auth = register(client)
    response = client.post("/api/v1/chem/depict", headers=auth, json={
        "smiles": BENZAMIDE, "width": 200, "height": 200, "highlight_atoms": [99],
    })
    assert response.status_code == 422


def test_a_structure_that_cannot_be_read_is_refused(client):
    auth = register(client)
    assert explain(client, auth, smiles="not a molecule").status_code == 422


def test_explaining_needs_a_signed_in_user(client):
    assert explain(client, {}).status_code == 401


def test_the_placeholder_model_says_it_cannot_explain_rather_than_inventing_one(make_client):
    """A fabricated explanation would be worse than none (report section 4.3.5)."""
    client = make_client(allow_stub=True)
    auth = register(client)
    response = explain(client, auth)
    assert response.status_code == 501
    assert "no trained model" in response.json()["detail"].lower()


def test_with_no_model_at_all_the_answer_is_the_same_as_for_predicting(make_client):
    client = make_client()
    auth = register(client)
    assert explain(client, auth).status_code == 503
