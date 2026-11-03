from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """All settings can be overridden with INHIBITAI_* environment variables or backend/.env."""

    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env",
        env_prefix="INHIBITAI_",
        extra="ignore",
        protected_namespaces=(),
    )

    # "production" refuses to start with unsafe settings (see production_problems).
    environment: Literal["development", "production"] = "development"

    # Database layer. PostgreSQL (optionally with the RDKit cartridge) is the target;
    # SQLite works for quick local runs and tests.
    database_url: str = f"sqlite:///{BACKEND_DIR / 'inhibitai.db'}"

    # Authentication. Leave empty in development to get a random per-process secret
    # (everyone is logged out on restart). Always set it in a shared/deployed server.
    jwt_secret: str = ""
    jwt_expiry_minutes: int = 60 * 24 * 7

    # Who may create an account: comma-separated emails and/or @domains, for example
    # "@sharjah.ac.ae, supervisor@gmail.com". Empty means anyone who can reach the server.
    allowed_signup_emails: str = ""

    # Inference service
    model_bundle_path: Path = BACKEND_DIR / "models" / "parp1_bundle.joblib"
    allow_stub_model: bool = False
    preload_model: bool = True
    skip_bundle_reference_check: bool = False

    # Chemistry service. Must match the preprocessing used to train the model.
    tautomer_canonicalization: bool = True
    max_tautomers: int = 200
    max_smiles_length: int = 1000
    max_heavy_atoms: int = 200

    # Display names given to each user's submissions, e.g. XYZ-001, XYZ-002, ...
    compound_prefix: str = "XYZ"
    target_name: str = "PARP-1"

    # Compound names from PubChem. Only the InChIKey (a one-way hash of the structure) is sent.
    pubchem_lookup: bool = True

    # Password reset emails. Without SMTP, development mode prints the codes in the server log and
    # production mode turns password reset off.
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_from: str = ""
    smtp_security: Literal["starttls", "ssl", "none"] = "starttls"

    # "Sign in with Google": the OAuth *web* client ID(s) the app asks Google for tokens for.
    google_client_ids: str = ""

    @field_validator("database_url")
    @classmethod
    def _name_the_postgres_driver(cls, url: str) -> str:
        """Hosting providers hand out postgres:// URLs; SQLAlchemy needs the psycopg 3 driver named."""
        for prefix in ("postgres://", "postgresql://"):
            if url.startswith(prefix):
                return "postgresql+psycopg://" + url[len(prefix):]
        return url

    @property
    def signup_allowlist(self) -> list[str]:
        return [entry.strip().lower() for entry in self.allowed_signup_emails.split(",") if entry.strip()]

    @property
    def google_client_id_list(self) -> list[str]:
        return [entry.strip() for entry in self.google_client_ids.split(",") if entry.strip()]

    def production_problems(self) -> list[str]:
        problems = []
        if len(self.jwt_secret.encode()) < 32:
            problems.append("INHIBITAI_JWT_SECRET must be set to a random string of at least 32 characters.")
        if self.database_url.startswith("sqlite"):
            problems.append("INHIBITAI_DATABASE_URL must point at PostgreSQL, not SQLite.")
        if self.allow_stub_model:
            problems.append("INHIBITAI_ALLOW_STUB_MODEL must be off: users would get placeholder predictions.")
        if not self.model_bundle_path.exists():
            problems.append(f"No model bundle at {self.model_bundle_path}.")
        return problems


@lru_cache
def get_settings() -> Settings:
    return Settings()
