from conftest import register

OLAPARIB = "O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12"
OLAPARIB_HCL_OTHER_TAUTOMER = "OC1=NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12.Cl"


def test_health_is_public_and_does_not_load_the_model(make_client, toy_bundle):
    client = make_client(toy_bundle)
    body = client.get("/api/v1/health").json()
    assert body["status"] == "ok"
    assert body["model"]["loaded"] is False


def test_register_login_and_me(make_client):
    client = make_client()
    register(client)
    response = client.post("/api/v1/auth/login", json={"email": "NOOR@example.com", "password": "correct-horse"})
    assert response.status_code == 200
    token = response.json()["access_token"]
    me = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"}).json()
    assert me["email"] == "noor@example.com"
    assert me["full_name"] == "Dr. Noor"


def test_auth_errors(make_client):
    client = make_client()
    register(client)
    assert client.post("/api/v1/auth/register", json={
        "email": "noor@example.com", "password": "another-password", "full_name": "X"}).status_code == 409
    assert client.post("/api/v1/auth/login", json={
        "email": "noor@example.com", "password": "wrong-password"}).status_code == 401
    short = client.post("/api/v1/auth/register", json={"email": "a@example.com", "password": "short", "full_name": "A"})
    assert short.status_code == 422
    assert short.json()["detail"].startswith("password:")
    assert client.get("/api/v1/predictions").status_code == 401
    assert client.get("/api/v1/predictions", headers={"Authorization": "Bearer junk"}).status_code == 401


def test_predict_caches_by_standardised_structure(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)

    first = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=auth)
    assert first.status_code == 201, first.text
    body = first.json()
    assert body["name"] == "XYZ-001"
    assert body["cached"] is False
    assert body["prediction"]["model_version"] == "toy-test-model"
    assert body["prediction"]["label"] == "active"
    assert body["prediction"]["in_domain"] is True
    assert body["prediction"]["reliability"] == "high"
    assert body["prediction"]["target"] == "PARP-1"

    # Same compound as a salt and in the other tautomeric form: served from the cache.
    second = client.post("/api/v1/predictions", json={"smiles": OLAPARIB_HCL_OTHER_TAUTOMER}, headers=auth).json()
    assert second["name"] == "XYZ-002"
    assert second["cached"] is True
    assert second["canonical_smiles"] == body["canonical_smiles"]
    assert second["input_smiles"] == OLAPARIB_HCL_OTHER_TAUTOMER

    # The cache is shared between users, but numbering and history are per user.
    other = register(client, email="iman@example.com")
    third = client.post("/api/v1/predictions", json={"smiles": OLAPARIB}, headers=other).json()
    assert third["cached"] is True and third["name"] == "XYZ-001"


def test_history_save_and_delete(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    a = client.post("/api/v1/predictions", json={"smiles": "NC(=O)c1ccccc1"}, headers=auth).json()
    b = client.post("/api/v1/predictions", json={"smiles": "CC(=O)Oc1ccccc1C(=O)O"}, headers=auth).json()

    page = client.get("/api/v1/predictions", headers=auth).json()
    assert page["total"] == 2
    assert [item["id"] for item in page["items"]] == [b["id"], a["id"]]  # newest first

    saved = client.patch(f"/api/v1/predictions/{a['id']}", json={"is_saved": True}, headers=auth).json()
    assert saved["is_saved"] is True
    assert client.get(f"/api/v1/predictions/{a['id']}", headers=auth).json()["is_saved"] is True

    assert client.delete(f"/api/v1/predictions/{a['id']}", headers=auth).status_code == 204
    assert client.get(f"/api/v1/predictions/{a['id']}", headers=auth).status_code == 404
    assert client.get("/api/v1/predictions", headers=auth).json()["total"] == 1


def test_users_cannot_see_each_others_history(make_client, toy_bundle):
    client = make_client(toy_bundle)
    noor = register(client)
    iman = register(client, email="iman@example.com")
    mine = client.post("/api/v1/predictions", json={"smiles": "NC(=O)c1ccccc1"}, headers=noor).json()
    assert client.get(f"/api/v1/predictions/{mine['id']}", headers=iman).status_code == 404
    assert client.delete(f"/api/v1/predictions/{mine['id']}", headers=iman).status_code == 404
    assert client.get("/api/v1/predictions", headers=iman).json()["total"] == 0


def test_invalid_smiles_is_rejected_with_a_reason(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    response = client.post("/api/v1/predictions", json={"smiles": "CN(C)(C)(C)C"}, headers=auth)
    assert response.status_code == 422
    assert "valence" in response.json()["detail"]


def test_no_model_means_503_not_a_fake_answer(make_client):
    client = make_client()  # no bundle, stub not allowed
    auth = register(client)
    response = client.post("/api/v1/predictions", json={"smiles": "NC(=O)c1ccccc1"}, headers=auth)
    assert response.status_code == 503
    assert "No model bundle" in response.json()["detail"]
    assert client.get("/api/v1/health").json()["status"] == "degraded"


def test_stub_model_when_allowed(make_client):
    client = make_client(allow_stub=True)
    auth = register(client)
    body = client.post("/api/v1/predictions", json={"smiles": "NC(=O)c1ccccc1"}, headers=auth).json()
    assert body["prediction"]["model_version"].startswith("stub")
    assert client.get("/api/v1/model", headers=auth).json()["is_stub"] is True


def test_model_info(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    info = client.get("/api/v1/model", headers=auth).json()
    assert info["model_version"] == "toy-test-model"
    assert info["conformal_significance"] == 0.2
    assert info["applicability_min_similarity"] == 0.5


def test_validate_and_depict(make_client):
    client = make_client()
    auth = register(client)
    ok = client.post("/api/v1/chem/validate", json={"smiles": "Oc1nncc2ccccc12.Cl"}, headers=auth).json()
    assert ok["valid"] is True
    assert ok["canonical_smiles"] == "O=c1[nH]ncc2ccccc12"
    bad = client.post("/api/v1/chem/validate", json={"smiles": "C1CC"}, headers=auth).json()
    assert bad["valid"] is False and bad["error"]

    png = client.post("/api/v1/chem/depict", json={"smiles": "NC(=O)c1ccccc1", "width": 200, "height": 150,
                                                    "theme": "dark"}, headers=auth)
    assert png.status_code == 200
    assert png.headers["content-type"] == "image/png"
    assert png.content.startswith(b"\x89PNG")
    svg = client.post("/api/v1/chem/depict", json={"smiles": "NC(=O)c1ccccc1", "format": "svg"}, headers=auth)
    assert svg.headers["content-type"].startswith("image/svg+xml")
    assert client.post("/api/v1/chem/depict", json={"smiles": "C1CC"}, headers=auth).status_code == 422
    assert client.post("/api/v1/chem/depict", json={"smiles": "C"}).status_code == 401


def test_similar_compounds_tested_before(make_client, toy_bundle):
    client = make_client(toy_bundle)
    auth = register(client)
    benzamide = client.post("/api/v1/predictions", json={"smiles": "NC(=O)c1ccccc1"}, headers=auth).json()
    client.post("/api/v1/predictions", json={"smiles": "CCCCCCCCCC"}, headers=auth)

    hits = client.post("/api/v1/predictions/similar", json={"smiles": "NC(=O)c1ccccc1", "min_similarity": 0.9},
                       headers=auth).json()
    assert [h["submission"]["id"] for h in hits] == [benzamide["id"]]
    assert hits[0]["similarity"] == 1.0

    near = client.post("/api/v1/predictions/similar", json={"smiles": "NC(=O)c1cccc(N)c1", "min_similarity": 0.3},
                       headers=auth).json()
    assert near and near[0]["submission"]["id"] == benzamide["id"]
    assert 0.3 <= near[0]["similarity"] < 1.0

    other = register(client, email="iman@example.com")
    assert client.post("/api/v1/predictions/similar", json={"smiles": "NC(=O)c1ccccc1"}, headers=other).json() == []
