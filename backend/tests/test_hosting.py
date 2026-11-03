import pytest

from app.config import Settings
from app.main import create_app
from app.services.ratelimit import RateLimiter
from conftest import register

SECRET = "a-production-secret-that-is-long-enough-123"


@pytest.mark.parametrize("given, expected", [
    ("postgres://u:p@db.example.com:5432/inhibitai", "postgresql+psycopg://u:p@db.example.com:5432/inhibitai"),
    ("postgresql://u:p@db.example.com/inhibitai", "postgresql+psycopg://u:p@db.example.com/inhibitai"),
    ("postgresql+psycopg://u:p@db/inhibitai", "postgresql+psycopg://u:p@db/inhibitai"),
    ("sqlite:///x.db", "sqlite:///x.db"),
])
def test_hosting_provider_database_urls_work(given, expected):
    assert Settings(_env_file=None, database_url=given).database_url == expected


def test_production_mode_refuses_unsafe_settings(tmp_path):
    settings = Settings(_env_file=None, environment="production", database_url=f"sqlite:///{tmp_path}/x.db",
                        jwt_secret="", allow_stub_model=True, model_bundle_path=tmp_path / "missing.joblib")
    with pytest.raises(RuntimeError) as error:
        create_app(settings)
    message = str(error.value)
    for problem in ("INHIBITAI_JWT_SECRET", "PostgreSQL", "INHIBITAI_ALLOW_STUB_MODEL", "No model bundle"):
        assert problem in message


def test_production_settings_that_pass(toy_bundle):
    settings = Settings(_env_file=None, environment="production", jwt_secret=SECRET,
                        database_url="postgres://u:p@db/inhibitai", model_bundle_path=toy_bundle)
    assert settings.production_problems() == []


def test_signup_can_be_limited_to_the_team(make_client):
    client = make_client(allowed_signup_emails="@sharjah.ac.ae, Supervisor@Example.com")
    outsider = client.post("/api/v1/auth/register", json={
        "email": "someone@gmail.com", "password": "long-enough-pw", "full_name": "Someone"})
    assert outsider.status_code == 403
    assert "invited" in outsider.json()["detail"]
    register(client, email="u22200731@sharjah.ac.ae")
    register(client, email="supervisor@example.com")


def test_repeated_logins_are_rate_limited(make_client):
    client = make_client()
    register(client)
    wrong = {"email": "noor@example.com", "password": "not-the-password"}
    for _ in range(10):
        assert client.post("/api/v1/auth/login", json=wrong).status_code == 401
    blocked = client.post("/api/v1/auth/login", json=wrong)
    assert blocked.status_code == 429
    assert int(blocked.headers["Retry-After"]) > 0
    # Other accounts are not locked out by someone guessing this one's password.
    register(client, email="iman@example.com")
    assert client.post("/api/v1/auth/login", json={
        "email": "iman@example.com", "password": "correct-horse"}).status_code == 200


def test_rate_limiter_window():
    limiter = RateLimiter(2, window_seconds=60)
    assert limiter.allow("a") and limiter.allow("a")
    assert not limiter.allow("a")
    assert limiter.allow("b")
    assert 0 < limiter.retry_after("a") <= 60
