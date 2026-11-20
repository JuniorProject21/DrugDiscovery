import re
import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app.services.google import GoogleTokenVerifier
from conftest import register

CLIENT_ID = "test-web-client.apps.googleusercontent.com"


class Outbox:
    def __init__(self):
        self.messages = []

    def send(self, to, subject, body):
        self.messages.append((to, subject, body))

    def last_code(self):
        return re.search(r"\b(\d{6})\b", self.messages[-1][2]).group(1)


@pytest.fixture
def outbox():
    return Outbox()


@pytest.fixture
def client(make_client, outbox):
    c = make_client()
    c.app.state.container.email_sender = outbox
    return c


def login(client, email="noor@example.com", password="Correct-horse9"):
    return client.post("/api/v1/auth/login", json={"email": email, "password": password})


# ---------- password reset ----------

def test_password_reset_with_emailed_code(client, outbox):
    register(client)
    sent = client.post("/api/v1/auth/password-reset/request", json={"email": "Noor@example.com"})
    assert sent.status_code == 202
    to, subject, body = outbox.messages[-1]
    assert to == "noor@example.com" and "reset" in subject.lower()

    wrong = client.post("/api/v1/auth/password-reset/confirm",
                        json={"email": "noor@example.com", "code": "000000", "new_password": "Brand-new-pass1"})
    assert wrong.status_code == 400

    done = client.post("/api/v1/auth/password-reset/confirm",
                       json={"email": "noor@example.com", "code": outbox.last_code(), "new_password": "Brand-new-pass1"})
    assert done.status_code == 200 and done.json()["access_token"]
    assert login(client, password="Brand-new-pass1").status_code == 200
    assert login(client).status_code == 401

    reused = client.post("/api/v1/auth/password-reset/confirm",
                         json={"email": "noor@example.com", "code": outbox.last_code(), "new_password": "Another-pass1"})
    assert reused.status_code == 400


def test_password_reset_does_not_reveal_accounts(client, outbox):
    response = client.post("/api/v1/auth/password-reset/request", json={"email": "nobody@example.com"})
    assert response.status_code == 202
    assert outbox.messages == []


def test_reset_code_stops_working_after_five_wrong_guesses(client, outbox):
    register(client)
    client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    code = outbox.last_code()
    for guess in ("111111", "222222", "333333", "444444", "555555"):
        if guess != code:
            client.post("/api/v1/auth/password-reset/confirm",
                        json={"email": "noor@example.com", "code": guess, "new_password": "Brand-new-pass1"})
    late = client.post("/api/v1/auth/password-reset/confirm",
                       json={"email": "noor@example.com", "code": code, "new_password": "Brand-new-pass1"})
    assert late.status_code == 400


def test_new_code_replaces_the_old_one(client, outbox):
    register(client)
    client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    first = outbox.last_code()
    client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    if first != outbox.last_code():
        stale = client.post("/api/v1/auth/password-reset/confirm",
                            json={"email": "noor@example.com", "code": first, "new_password": "Brand-new-pass1"})
        assert stale.status_code == 400


def test_password_reset_off_without_email(client):
    client.app.state.container.email_sender = None
    response = client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    assert response.status_code == 503


# ---------- account details ----------

def test_change_name_and_password(client):
    auth = register(client)
    me = client.patch("/api/v1/auth/me", json={"full_name": "  Dr. Noor Ahmed "}, headers=auth).json()
    assert me["full_name"] == "Dr. Noor Ahmed"
    assert me["has_password"] is True and me["google_linked"] is False

    bad = client.post("/api/v1/auth/change-password",
                      json={"current_password": "wrong", "new_password": "Brand-new-pass1"}, headers=auth)
    assert bad.status_code == 400
    ok = client.post("/api/v1/auth/change-password",
                     json={"current_password": "Correct-horse9", "new_password": "Brand-new-pass1"}, headers=auth)
    assert ok.status_code == 204
    assert login(client, password="Brand-new-pass1").status_code == 200


# ---------- idle sessions (report Table 6-5) ----------

def _quiet_for(client, minutes: float) -> None:
    """Pretends the account made no request for [minutes], by moving its last-seen time back."""
    from datetime import datetime, timedelta, timezone

    from app.data.models import User

    with client.app.state.container.session_factory() as session:
        user = session.query(User).first()
        user.last_seen_at = datetime.now(timezone.utc) - timedelta(minutes=minutes)
        session.commit()


def test_a_session_left_idle_is_refused(client):
    auth = register(client)
    assert client.get("/api/v1/auth/me", headers=auth).status_code == 200

    _quiet_for(client, 31)

    timed_out = client.get("/api/v1/auth/me", headers=auth)
    assert timed_out.status_code == 401
    assert "timed out" in timed_out.json()["detail"]


def test_a_session_still_in_use_is_not_refused(client):
    auth = register(client)
    client.get("/api/v1/auth/me", headers=auth)

    _quiet_for(client, 29)

    assert client.get("/api/v1/auth/me", headers=auth).status_code == 200, "29 minutes is inside the window"
    _quiet_for(client, 29)
    assert client.get("/api/v1/auth/me", headers=auth).status_code == 200, "each request restarts the clock"


def test_signing_in_again_after_a_timeout_works(client):
    auth = register(client)
    client.get("/api/v1/auth/me", headers=auth)
    _quiet_for(client, 31)
    assert client.get("/api/v1/auth/me", headers=auth).status_code == 401

    fresh = login(client)
    assert fresh.status_code == 200
    new_auth = {"Authorization": f"Bearer {fresh.json()['access_token']}"}
    assert client.get("/api/v1/auth/me", headers=new_auth).status_code == 200


def test_the_idle_window_can_be_turned_off(make_client, outbox):
    """Zero means no idle check, for a deployment that would rather not sign people out."""
    client = make_client(session_idle_minutes=0)
    client.app.state.container.email_sender = outbox
    auth = register(client)
    client.get("/api/v1/auth/me", headers=auth)

    _quiet_for(client, 60 * 24)

    assert client.get("/api/v1/auth/me", headers=auth).status_code == 200


# ---------- password policy (report Table 6-5) ----------

@pytest.mark.parametrize("password, why", [
    ("Short1!", "under twelve characters"),
    ("abcdefghijkl", "twelve, but lower case only"),
    ("ABCDEFGHIJKL", "twelve, but upper case only"),
    ("123456789012", "twelve, but digits only"),
    ("passwordword", "a long word is still one kind"),
])
def test_a_weak_password_is_refused_when_registering(client, password, why):
    refused = client.post("/api/v1/auth/register", json={
        "email": "new@example.com", "password": password, "full_name": "New"})
    assert refused.status_code == 422, why
    assert refused.json()["detail"].startswith("password:")


@pytest.mark.parametrize("password", [
    "Correct-horse9",      # upper, lower, digit
    "correct horse 9",     # lower, digit, space counts as a symbol
    "PARP1-inhibitor",     # upper, lower, digit
    "aaaaaaaaaaaA1",       # dull, but it does mix three kinds
])
def test_a_password_that_mixes_three_kinds_is_accepted(client, password):
    created = client.post("/api/v1/auth/register", json={
        "email": f"{abs(hash(password))}@example.com", "password": password, "full_name": "New"})
    assert created.status_code == 201, created.text


def test_the_rule_also_applies_when_changing_a_password(client):
    auth = register(client)  # already the Authorization header
    refused = client.post("/api/v1/auth/change-password",
                          json={"current_password": "Correct-horse9", "new_password": "weakweakweak"},
                          headers=auth)
    assert refused.status_code == 422
    assert login(client).status_code == 200, "the old password still works after a refused change"


def test_tightening_the_rule_does_not_lock_anyone_out(client):
    """The policy is checked when a password is *set*, never when it is used. An account
    made before the rule changed therefore still signs in."""
    register(client)
    with_short = login(client, password="short")
    assert with_short.status_code == 401, "refused as wrong credentials, not as a malformed request"


# ---------- Google ----------

class FakeJwks:
    """Stands in for Google's public-key endpoint."""

    def __init__(self, public_key):
        self.public_key = public_key

    def get_signing_key_from_jwt(self, token):
        return type("Key", (), {"key": self.public_key})()


@pytest.fixture(scope="module")
def google_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def google_token(key, **overrides):
    now = int(time.time())
    claims = {"iss": "https://accounts.google.com", "aud": CLIENT_ID, "sub": "google-123", "iat": now,
              "exp": now + 600, "email": "u22200731@sharjah.ac.ae", "email_verified": True, "name": "Rawan Yasir"}
    claims.update(overrides)
    return jwt.encode(claims, key, algorithm="RS256", headers={"kid": "test"})


@pytest.fixture
def google_client(client, google_key):
    client.app.state.container.google = GoogleTokenVerifier([CLIENT_ID], jwks_client=FakeJwks(google_key.public_key()))
    return client


def test_google_sign_in_creates_then_reuses_an_account(google_client, google_key):
    first = google_client.post("/api/v1/auth/google", json={"id_token": google_token(google_key)})
    assert first.status_code == 200, first.text
    body = first.json()
    assert body["created"] is True
    assert body["user"]["full_name"] == "Rawan Yasir"
    assert body["user"]["has_password"] is False and body["user"]["google_linked"] is True

    again = google_client.post("/api/v1/auth/google", json={"id_token": google_token(google_key)}).json()
    assert again["created"] is False and again["user"]["id"] == body["user"]["id"]

    # No password yet: email login explains, and a first password can be set without a current one.
    refused = login(google_client, email="u22200731@sharjah.ac.ae", password="Anything-at-all1")
    assert refused.status_code == 401 and "Google" in refused.json()["detail"]
    auth = {"Authorization": f"Bearer {body['access_token']}"}
    assert google_client.post("/api/v1/auth/change-password", json={"new_password": "Now-i-have-one1"},
                              headers=auth).status_code == 204
    assert login(google_client, email="u22200731@sharjah.ac.ae", password="Now-i-have-one1").status_code == 200


def test_google_links_to_an_existing_email_account(google_client, google_key):
    register(google_client, email="u22200731@sharjah.ac.ae")
    body = google_client.post("/api/v1/auth/google", json={"id_token": google_token(google_key)}).json()
    assert body["created"] is False
    assert body["user"]["has_password"] is True and body["user"]["google_linked"] is True


@pytest.mark.parametrize("claims", [
    {"aud": "someone-elses-client"},
    {"iss": "https://evil.example.com"},
    {"email_verified": False},
    {"exp": int(time.time()) - 10},
])
def test_bad_google_tokens_are_rejected(google_client, google_key, claims):
    response = google_client.post("/api/v1/auth/google", json={"id_token": google_token(google_key, **claims)})
    assert response.status_code == 401


def test_google_token_signed_by_another_key_is_rejected(google_client):
    stranger = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    response = google_client.post("/api/v1/auth/google", json={"id_token": google_token(stranger)})
    assert response.status_code == 401


def test_google_respects_the_signup_allowlist(make_client, google_key):
    c = make_client(allowed_signup_emails="@sharjah.ac.ae")
    c.app.state.container.google = GoogleTokenVerifier([CLIENT_ID], jwks_client=FakeJwks(google_key.public_key()))
    outsider = c.post("/api/v1/auth/google", json={"id_token": google_token(google_key, email="x@gmail.com",
                                                                            sub="google-999")})
    assert outsider.status_code == 403


def test_google_not_configured(client):
    assert client.post("/api/v1/auth/google", json={"id_token": "x"}).status_code == 503


# ---------- the rest of the profile ----------

def test_profile_details_are_saved_and_returned(make_client):
    client = make_client()
    auth = register(client)
    response = client.patch("/api/v1/auth/me", headers=auth, json={
        "full_name": "Dr. Noor Albarakati",
        "institution": "University of Sharjah",
        "role": "researcher",
        "research_area": "PARP inhibitors, breast cancer",
        "age": 24,
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["institution"] == "University of Sharjah"
    assert body["role"] == "researcher"
    assert body["research_area"] == "PARP inhibitors, breast cancer"
    assert body["age"] == 24
    assert client.get("/api/v1/auth/me", headers=auth).json()["institution"] == "University of Sharjah"


def test_sending_one_field_leaves_the_others_alone(make_client):
    client = make_client()
    auth = register(client)
    client.patch("/api/v1/auth/me", headers=auth, json={"institution": "UoS", "age": 24})
    body = client.patch("/api/v1/auth/me", headers=auth, json={"age": 25}).json()
    assert body["age"] == 25
    assert body["institution"] == "UoS"
    assert body["full_name"] == "Dr. Noor"


def test_an_empty_value_takes_a_detail_back_off_the_profile(make_client):
    client = make_client()
    auth = register(client)
    client.patch("/api/v1/auth/me", headers=auth, json={"institution": "UoS", "role": "student"})
    body = client.patch("/api/v1/auth/me", headers=auth, json={"institution": "", "role": ""}).json()
    assert body["institution"] is None
    assert body["role"] is None


def test_a_new_account_simply_has_no_profile_details(make_client):
    client = make_client()
    auth = register(client)
    me = client.get("/api/v1/auth/me", headers=auth).json()
    assert me["institution"] is None and me["role"] is None
    assert me["research_area"] is None and me["age"] is None


def test_nonsense_profile_values_are_refused(make_client):
    client = make_client()
    auth = register(client)
    assert client.patch("/api/v1/auth/me", headers=auth, json={"age": 7}).status_code == 422
    assert client.patch("/api/v1/auth/me", headers=auth, json={"age": 200}).status_code == 422
    assert client.patch("/api/v1/auth/me", headers=auth, json={"role": "wizard"}).status_code == 422
    assert client.patch("/api/v1/auth/me", headers=auth,
                        json={"institution": "x" * 201}).status_code == 422
    # The name still cannot be blanked out: every account needs one.
    assert client.patch("/api/v1/auth/me", headers=auth, json={"full_name": "  "}).status_code == 422
