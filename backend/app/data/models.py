"""Database tables (Database layer): users, molecules, predictions, submissions."""

from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    Text,
    TypeDecorator,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class UTCDateTime(TypeDecorator):
    """Timezone-aware UTC datetimes on every backend (SQLite drops the offset otherwise)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value

    def process_result_value(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value


class Base(DeclarativeBase):
    pass


# Stored instead of a bcrypt hash for accounts that only sign in with Google; never matches a password.
NO_PASSWORD = "!no-password"


class User(Base):
    __tablename__ = "users"
    __table_args__ = (Index("ix_users_google_sub", "google_sub", unique=True),)

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(100))
    full_name: Mapped[str] = mapped_column(String(200))
    # Google's stable account id ("sub" claim) once the user has signed in with Google.
    google_sub: Mapped[str | None] = mapped_column(String(255))
    # Counter behind the per-user display names (XYZ-001, XYZ-002, ...).
    next_compound_number: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    @property
    def has_password(self) -> bool:
        return self.password_hash != NO_PASSWORD


class PasswordResetCode(Base):
    """A 6-digit code emailed to reset a password. Only an HMAC of the code is stored."""

    __tablename__ = "password_reset_codes"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    used_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Molecule(Base):
    """One row per distinct standardised structure, shared by all users (the prediction cache key).

    When the RDKit cartridge is available, `mol` (mol) and `mfp2` (bfp) columns are added on top of
    this table by `db.enable_rdkit_cartridge`, generated from `canonical_smiles` and `fingerprint`.
    """

    __tablename__ = "molecules"

    id: Mapped[int] = mapped_column(primary_key=True)
    canonical_smiles: Mapped[str] = mapped_column(Text, unique=True)
    inchikey: Mapped[str | None] = mapped_column(String(27), index=True)
    # Morgan radius 2 / 2048 bits as a raw bit string (the cartridge's bfp format), for similarity search.
    fingerprint: Mapped[bytes] = mapped_column(LargeBinary)
    formula: Mapped[str | None] = mapped_column(String(100))
    molecular_weight: Mapped[float | None] = mapped_column(Float)
    # Names from PubChem, looked up by InChIKey. names_checked_at stays empty until PubChem has
    # answered (found or not found), so a failed lookup is retried next time.
    common_name: Mapped[str | None] = mapped_column(String(200))
    iupac_name: Mapped[str | None] = mapped_column(Text)
    pubchem_cid: Mapped[int | None] = mapped_column(Integer)
    names_checked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Prediction(Base):
    """Model output for a molecule. Unique per (molecule, model version), so it doubles as a cache."""

    __tablename__ = "predictions"
    __table_args__ = (UniqueConstraint("molecule_id", "model_version", name="uq_prediction_molecule_model"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    molecule_id: Mapped[int] = mapped_column(ForeignKey("molecules.id", ondelete="CASCADE"), index=True)
    model_version: Mapped[str] = mapped_column(String(100))
    label: Mapped[str] = mapped_column(String(16))  # active | inactive | uncertain
    score: Mapped[float] = mapped_column(Float)  # fraction of trees voting active (not a calibrated probability)
    score_std: Mapped[float | None] = mapped_column(Float)  # spread across ensemble members
    confidence: Mapped[float] = mapped_column(Float)
    threshold: Mapped[float] = mapped_column(Float)
    p_value_active: Mapped[float | None] = mapped_column(Float)
    p_value_inactive: Mapped[float | None] = mapped_column(Float)
    nearest_similarity: Mapped[float | None] = mapped_column(Float)
    in_domain: Mapped[bool | None] = mapped_column(Boolean)
    reliability: Mapped[str | None] = mapped_column(String(8))  # high | medium | low, from nearest_similarity
    pic50: Mapped[float | None] = mapped_column(Float)
    adme: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class Submission(Base):
    """A user's request to predict a compound: one row per entry in their history."""

    __tablename__ = "submissions"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    molecule_id: Mapped[int] = mapped_column(ForeignKey("molecules.id"), index=True)
    prediction_id: Mapped[int] = mapped_column(ForeignKey("predictions.id"))
    name: Mapped[str] = mapped_column(String(50))
    input_smiles: Mapped[str] = mapped_column(Text)
    is_saved: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, index=True)

    molecule: Mapped[Molecule] = relationship(lazy="joined")
    prediction: Mapped[Prediction] = relationship(lazy="joined")
