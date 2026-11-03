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


def login(client, email="noor@example.com", password="correct-horse"):
    return client.post("/api/v1/auth/login", json={"email": email, "password": password})


# ---------- password reset ----------

def test_password_reset_with_emailed_code(client, outbox):
    register(client)
    sent = client.post("/api/v1/auth/password-reset/request", json={"email": "Noor@example.com"})
    assert sent.status_code == 202
    to, subject, body = outbox.messages[-1]
    assert to == "noor@example.com" and "reset" in subject.lower()

    wrong = client.post("/api/v1/auth/password-reset/confirm",
                        json={"email": "noor@example.com", "code": "000000", "new_password": "brand-new-pass"})
    assert wrong.status_code == 400

    done = client.post("/api/v1/auth/password-reset/confirm",
                       json={"email": "noor@example.com", "code": outbox.last_code(), "new_password": "brand-new-pass"})
    assert done.status_code == 200 and done.json()["access_token"]
    assert login(client, password="brand-new-pass").status_code == 200
    assert login(client).status_code == 401

    reused = client.post("/api/v1/auth/password-reset/confirm",
                         json={"email": "noor@example.com", "code": outbox.last_code(), "new_password": "another-pass"})
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
                        json={"email": "noor@example.com", "code": guess, "new_password": "brand-new-pass"})
    late = client.post("/api/v1/auth/password-reset/confirm",
                       json={"email": "noor@example.com", "code": code, "new_password": "brand-new-pass"})
    assert late.status_code == 400


def test_new_code_replaces_the_old_one(client, outbox):
    register(client)
    client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    first = outbox.last_code()
    client.post("/api/v1/auth/password-reset/request", json={"email": "noor@example.com"})
    if first != outbox.last_code():
        stale = client.post("/api/v1/auth/password-reset/confirm",
                            json={"email": "noor@example.com", "code": first, "new_password": "brand-new-pass"})
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
                      json={"current_password": "wrong", "new_password": "brand-new-pass"}, headers=auth)
    assert bad.status_code == 400
    ok = client.post("/api/v1/auth/change-password",
                     json={"current_password": "correct-horse", "new_password": "brand-new-pass"}, headers=auth)
    assert ok.status_code == 204
    assert login(client, password="brand-new-pass").status_code == 200


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
    refused = login(google_client, email="u22200731@sharjah.ac.ae", password="anything-at-all")
    assert refused.status_code == 401 and "Google" in refused.json()["detail"]
    auth = {"Authorization": f"Bearer {body['access_token']}"}
    assert google_client.post("/api/v1/auth/change-password", json={"new_password": "now-i-have-one"},
                              headers=auth).status_code == 204
    assert login(google_client, email="u22200731@sharjah.ac.ae", password="now-i-have-one").status_code == 200


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
