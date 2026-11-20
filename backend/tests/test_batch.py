"""Screening a whole file of SMILES at once (progress report section 6.6 did this by hand)."""

from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"
ASPIRIN = "CC(=O)Oc1ccccc1C(=O)O"
CAFFEINE = "Cn1cnc2c1c(=O)n(C)c(=O)n2C"


def batch(client, auth, smiles, labels=None, save=False):
    body = {"smiles": smiles, "save": save}
    if labels is not None:
        body["labels"] = labels
    return client.post("/api/v1/predictions/batch", json=body, headers=auth)


def test_a_file_of_compounds_is_screened_in_one_request(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)

    response = batch(client, auth, [OLAPARIB, ASPIRIN, CAFFEINE], labels=["F1", "F2", "F3"])
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["total"], body["succeeded"], body["failed"]) == (3, 3, 0)
    assert body["saved"] is False

    rows = body["rows"]
    # The rows come back in the order of the file, so a row lines up with its line.
    assert [r["index"] for r in rows] == [0, 1, 2]
    assert [r["label_from_file"] for r in rows] == ["F1", "F2", "F3"]
    assert all(r["ok"] for r in rows)
    assert all(r["prediction"]["model_version"] == "toy-test-model" for r in rows)
    assert all(r["prediction"]["target"] == "PARP-1" for r in rows)
    assert rows[0]["formula"] == "C24H23FN4O3"
    assert rows[0]["molecular_weight"] > 400


def test_a_bad_row_does_not_lose_the_rest_of_the_file(make_client, toy_bundle):
    """The point of the table: one unreadable line shows its reason, the others still get scores."""
    client = make_client(toy_bundle)
    auth = register(client)

    body = batch(client, auth, [OLAPARIB, "this is not a molecule", ASPIRIN]).json()
    assert (body["total"], body["succeeded"], body["failed"]) == (3, 1 + 1, 1)
    bad = body["rows"][1]
    assert bad["ok"] is False
    assert bad["error"]
    assert bad["prediction"] is None
    assert bad["input_smiles"] == "this is not a molecule"
    assert [r["ok"] for r in body["rows"]] == [True, False, True]


def test_the_same_structure_twice_is_only_predicted_once(make_client, toy_bundle):
    """Rows share the prediction cache with single predictions, so a repeat costs nothing."""
    client = make_client(toy_bundle)
    auth = register(client)
    salt = "OC1=NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12.Cl"

    rows = batch(client, auth, [OLAPARIB, salt]).json()["rows"]
    assert rows[0]["canonical_smiles"] == rows[1]["canonical_smiles"]
    assert rows[0]["prediction"] == rows[1]["prediction"]


def test_rows_are_not_saved_to_the_history_unless_asked(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)

    rows = batch(client, auth, [OLAPARIB, ASPIRIN]).json()["rows"]
    assert all(r["submission_id"] is None for r in rows)
    assert client.get("/api/v1/predictions", headers=auth).json()["total"] == 0


def test_saving_a_batch_puts_every_row_in_the_history(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)

    body = batch(client, auth, [OLAPARIB, "nonsense", ASPIRIN], labels=["16F", None, None], save=True).json()
    assert body["saved"] is True
    rows = body["rows"]
    # The file's own name is kept; a row without one gets the usual XYZ-### label.
    assert rows[0]["name"] == "16F"
    assert rows[2]["name"] == "XYZ-001"
    assert rows[1]["submission_id"] is None  # the row that could not be read

    history = client.get("/api/v1/predictions", headers=auth).json()
    assert history["total"] == 2
    assert {s["id"] for s in history["items"]} == {rows[0]["submission_id"], rows[2]["submission_id"]}
    # And each saved row opens as an ordinary prediction.
    one = client.get(f"/api/v1/predictions/{rows[0]['submission_id']}", headers=auth)
    assert one.status_code == 200
    assert one.json()["name"] == "16F"


def test_labels_must_line_up_with_the_smiles(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    response = batch(client, auth, [OLAPARIB, ASPIRIN], labels=["only one"])
    assert response.status_code == 422
    assert "same number" in response.json()["detail"]


def test_an_empty_or_oversized_file_is_refused(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    assert batch(client, auth, []).status_code == 422
    assert batch(client, auth, [ASPIRIN] * 501).status_code == 422


def test_batch_needs_a_signed_in_user_and_a_model(make_client, toy_bundle):
    assert batch(make_client(toy_bundle), {}, [ASPIRIN]).status_code == 401

    no_model = make_client()  # no bundle on disk and stub not allowed
    auth = register(no_model)
    assert batch(no_model, auth, [ASPIRIN]).status_code == 503
