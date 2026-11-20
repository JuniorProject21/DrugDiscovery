"""Signing up and signing in with a phone number (progress report figures 11-3 and 11-5)."""

import re

import pytest

from app.services.phone import InvalidPhoneError, mask_phone, normalise_phone

PHONE = "+971501234567"
PASSWORD = "Correct-horse9"


class Inbox:
    """Stands in for an SMS provider: keeps the messages instead of sending them."""

    def __init__(self):
        self.messages = []

    def send(self, to, body):
        self.messages.append((to, body))

    def last_code(self):
        return re.search(r"\b(\d{6})\b", self.messages[-1][1]).group(1)


@pytest.fixture
def inbox():
    return Inbox()


@pytest.fixture
def client(make_client, inbox):
    c = make_client()
    c.app.state.container.sms_sender = inbox
    return c


def start(client, phone=PHONE, password=PASSWORD, name="Dr. Noor"):
    return client.post("/api/v1/auth/phone/sign-up",
                       json={"phone": phone, "password": password, "full_name": name})


def verify(client, code, phone=PHONE):
    return client.post("/api/v1/auth/phone/verify", json={"phone": phone, "code": code})


def login(client, phone=PHONE, password=PASSWORD):
    return client.post("/api/v1/auth/phone/login", json={"phone": phone, "password": password})


# ---------- normalising what people type ----------

@pytest.mark.parametrize("written", [
    "+971501234567",
    "+971 50 123 4567",
    "+971-50-123-4567",
    "00971501234567",
    "+971 (50) 123 4567",
])
def test_the_same_number_written_different_ways(written):
    assert normalise_phone(written) == PHONE


def test_a_national_number_needs_a_country_code():
    with pytest.raises(InvalidPhoneError, match="country code"):
        normalise_phone("0501234567")
    # ... unless the server is told which country to assume.
    assert normalise_phone("050 123 4567", default_country_code="+971") == PHONE


@pytest.mark.parametrize("bad", ["", "   ", "+", "+0501234567", "+97150abc4567", "+12", "+" + "9" * 20])
def test_numbers_that_are_not_phone_numbers(bad):
    with pytest.raises(InvalidPhoneError):
        normalise_phone(bad)


def test_masking_keeps_only_the_ends():
    masked = mask_phone(PHONE)
    assert masked.startswith("+971") and masked.endswith("4567") and "1234567" not in masked


# ---------- signing up ----------

def test_sign_up_verify_and_sign_in(client, inbox):
    response = start(client)
    assert response.status_code == 202, response.text
    assert "4567" in response.json()["detail"]
    assert inbox.messages[0][0] == PHONE

    # The code is not a sign-in on its own: the account does not work until it is entered.
    assert login(client).status_code == 403

    verified = verify(client, inbox.last_code())
    assert verified.status_code == 201, verified.text
    body = verified.json()
    assert body["user"]["phone"] == PHONE
    assert body["user"]["email"] is None
    assert body["created"] is True

    me = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert me.json()["full_name"] == "Dr. Noor"
    assert login(client).status_code == 200


def test_the_number_is_stored_in_one_form_however_it_was_typed(client, inbox):
    start(client, phone="+971 50 123 4567")
    verify(client, inbox.last_code(), phone="00971501234567")
    assert login(client, phone="+971-50-123-4567").status_code == 200


def test_an_unfinished_sign_up_can_be_started_again(client, inbox):
    start(client, name="Typo", password="First-password1")
    first_code = inbox.last_code()
    start(client, name="Dr. Noor", password="Second-password2")
    # The first code stops working, and the new name and password are the ones that count.
    assert verify(client, first_code).status_code == 401
    assert verify(client, inbox.last_code()).json()["user"]["full_name"] == "Dr. Noor"
    assert login(client, password="First-password1").status_code == 401
    assert login(client, password="Second-password2").status_code == 200


def test_a_verified_number_cannot_be_taken_over(client, inbox):
    start(client)
    verify(client, inbox.last_code())
    assert start(client, password="Attacker-password1").status_code == 409


def test_wrong_codes_stop_working_after_five_tries(client, inbox):
    start(client)
    code = inbox.last_code()
    wrong = "000000" if code != "000000" else "111111"
    for _ in range(5):
        assert verify(client, wrong).status_code == 401
    # Even the right code is refused now; the user has to ask for a new one.
    assert verify(client, code).status_code == 401
    client.post("/api/v1/auth/phone/resend", json={"phone": PHONE})
    assert verify(client, inbox.last_code()).status_code == 201


def test_resending_says_nothing_about_who_has_an_account(client, inbox):
    unknown = client.post("/api/v1/auth/phone/resend", json={"phone": "+971509999999"})
    start(client)
    known = client.post("/api/v1/auth/phone/resend", json={"phone": PHONE})
    assert unknown.status_code == known.status_code == 202
    assert unknown.json()["detail"].replace("9999", "4567") == known.json()["detail"]


def test_badly_written_numbers_are_refused_with_a_reason(client):
    response = start(client, phone="0501234567")
    assert response.status_code == 422
    assert "country code" in response.json()["detail"]


def test_phone_and_email_accounts_live_side_by_side(client, inbox):
    from conftest import register

    register(client, email="noor@example.com")
    start(client)
    verify(client, inbox.last_code())
    assert login(client).status_code == 200
    assert client.post("/api/v1/auth/login",
                       json={"email": "noor@example.com", "password": "Correct-horse9"}).status_code == 200


def test_sign_up_respects_the_email_allowlist(make_client, inbox):
    """An allowlist names email addresses, so phone sign-up has no way to match it and is refused."""
    client = make_client(allowed_signup_emails="@uni.ac.ae")
    client.app.state.container.sms_sender = inbox
    response = start(client)
    assert response.status_code == 403
    assert "email" in response.json()["detail"]


# ---------- resetting a forgotten password by text ----------

def reset_request(client, phone=PHONE):
    return client.post("/api/v1/auth/phone/password-reset/request", json={"phone": phone})


def reset_confirm(client, code, new_password="Brand-new-password1", phone=PHONE):
    return client.post("/api/v1/auth/phone/password-reset/confirm",
                       json={"phone": phone, "code": code, "new_password": new_password})


def signed_up(client, inbox):
    start(client)
    verify(client, inbox.last_code())


def test_a_forgotten_password_can_be_reset_by_text(client, inbox):
    signed_up(client, inbox)
    assert reset_request(client).status_code == 202

    response = reset_confirm(client, inbox.last_code())
    assert response.status_code == 200, response.text
    assert response.json()["user"]["phone"] == PHONE  # the reset signs you straight in
    assert login(client, password="Brand-new-password1").status_code == 200
    assert login(client, password=PASSWORD).status_code == 401  # the old one stops working


def test_the_reset_code_cannot_be_guessed(client, inbox):
    signed_up(client, inbox)
    reset_request(client)
    code = inbox.last_code()
    wrong = "000000" if code != "000000" else "111111"
    for _ in range(5):
        assert reset_confirm(client, wrong).status_code == 400
    # Five wrong tries burn the code, even for someone who then gets it right.
    assert reset_confirm(client, code).status_code == 400


def test_a_used_reset_code_does_not_work_twice(client, inbox):
    signed_up(client, inbox)
    reset_request(client)
    code = inbox.last_code()
    assert reset_confirm(client, code).status_code == 200
    assert reset_confirm(client, code, new_password="Third-password3").status_code == 400


def test_requesting_a_reset_says_nothing_about_who_has_an_account(client, inbox):
    signed_up(client, inbox)
    known = reset_request(client)
    unknown = reset_request(client, phone="+971509999999")
    assert known.status_code == unknown.status_code == 202
    assert unknown.json()["detail"].replace("9999", "4567") == known.json()["detail"]


def test_an_unverified_number_cannot_reset_its_way_in(client, inbox):
    """Otherwise the reset code would be a way around finishing the sign-up."""
    start(client)  # started but never verified
    messages_before = len(inbox.messages)
    assert reset_request(client).status_code == 202
    assert len(inbox.messages) == messages_before, "no reset code should have been sent"
    assert reset_confirm(client, inbox.last_code()).status_code == 400
