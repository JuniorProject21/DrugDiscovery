"""JSON shapes exchanged with the Android app."""

from datetime import datetime
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, EmailStr, Field

from app.data.models import Prediction, Submission, User

SMILES_MAX = 1000


def _fits_bcrypt(value: str) -> str:
    if len(value.encode()) > 72:
        raise ValueError("Password is too long (maximum 72 bytes).")
    return value


def _not_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("This can't be empty.")
    return value.strip()


NewPassword = Annotated[str, Field(min_length=8, max_length=72), AfterValidator(_fits_bcrypt)]
PersonName = Annotated[str, Field(min_length=1, max_length=200), AfterValidator(_not_blank)]
CompoundLabel = Annotated[str, Field(min_length=1, max_length=50), AfterValidator(_not_blank)]


# ---------- auth ----------

class RegisterRequest(BaseModel):
    email: EmailStr
    password: NewPassword
    full_name: PersonName


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=72)


class UserOut(BaseModel):
    id: int
    email: str
    full_name: str
    created_at: datetime
    has_password: bool = Field(description="False for accounts that have only ever signed in with Google")
    google_linked: bool

    @classmethod
    def of(cls, user: User) -> "UserOut":
        return cls(id=user.id, email=user.email, full_name=user.full_name, created_at=user.created_at,
                   has_password=user.has_password, google_linked=user.google_sub is not None)


class TokenResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int
    user: UserOut
    created: bool = Field(False, description="True when Google sign-in created a new account")


class GoogleSignInRequest(BaseModel):
    id_token: str = Field(min_length=1, max_length=8192)


class PasswordResetRequest(BaseModel):
    email: EmailStr


class PasswordResetConfirm(BaseModel):
    email: EmailStr
    code: str = Field(pattern=r"^\d{6}$")
    new_password: NewPassword


class ProfileUpdate(BaseModel):
    full_name: PersonName


class PasswordChange(BaseModel):
    current_password: str | None = Field(None, max_length=72)
    new_password: NewPassword


class MessageResponse(BaseModel):
    detail: str


# ---------- chemistry ----------

class SmilesRequest(BaseModel):
    smiles: str = Field(min_length=1, max_length=SMILES_MAX)


class ValidateResponse(BaseModel):
    valid: bool
    error: str | None = None
    canonical_smiles: str | None = None
    inchikey: str | None = None
    formula: str | None = None
    molecular_weight: float | None = None
    heavy_atoms: int | None = None
    common_name: str | None = Field(None, description="From PubChem, when the compound is known there")
    iupac_name: str | None = None
    pubchem_cid: int | None = None


class DepictRequest(SmilesRequest):
    width: int = Field(300, ge=32, le=1200)
    height: int = Field(300, ge=32, le=1200)
    theme: Literal["light", "dark"] = "light"
    format: Literal["png", "svg"] = "png"


# ---------- predictions ----------

class PredictionOut(BaseModel):
    label: Literal["active", "inactive", "uncertain"]
    score: float = Field(description="Fraction of trees voting active, 0-1. Not a probability of being right")
    score_std: float | None = Field(description="Spread of the score across ensemble members")
    confidence: float
    threshold: float
    p_value_active: float | None
    p_value_inactive: float | None
    nearest_similarity: float | None = Field(description="Tanimoto similarity to the closest training compound")
    in_domain: bool | None = Field(description="False when the compound is unlike the training chemistry")
    reliability: Literal["high", "medium", "low"] | None = Field(
        description="From nearest_similarity: high >= 0.7, medium >= 0.5, low below (bands set by the model bundle)")
    pic50: float | None
    adme: dict | None
    model_version: str
    target: str

    @classmethod
    def of(cls, p: Prediction, target: str) -> "PredictionOut":
        return cls(
            label=p.label, score=p.score, score_std=p.score_std, confidence=p.confidence,
            threshold=p.threshold, p_value_active=p.p_value_active, p_value_inactive=p.p_value_inactive,
            nearest_similarity=p.nearest_similarity, in_domain=p.in_domain, reliability=p.reliability,
            pic50=p.pic50, adme=p.adme,
            model_version=p.model_version, target=target,
        )


class SubmissionOut(BaseModel):
    id: int
    name: str = Field(description="The compound's name if PubChem knows it, else XYZ-###; users can rename it")
    input_smiles: str
    canonical_smiles: str
    common_name: str | None
    iupac_name: str | None
    pubchem_cid: int | None
    formula: str | None
    molecular_weight: float | None
    is_saved: bool
    created_at: datetime
    prediction: PredictionOut

    @classmethod
    def of(cls, s: Submission, target: str, **extra) -> "SubmissionOut":
        m = s.molecule
        return cls(
            id=s.id, name=s.name, input_smiles=s.input_smiles, canonical_smiles=m.canonical_smiles,
            common_name=m.common_name, iupac_name=m.iupac_name, pubchem_cid=m.pubchem_cid, formula=m.formula,
            molecular_weight=m.molecular_weight, is_saved=s.is_saved, created_at=s.created_at,
            prediction=PredictionOut.of(s.prediction, target), **extra,
        )


class PredictResponse(SubmissionOut):
    cached: bool = Field(description="True when an earlier prediction for the same structure was reused")


class SubmissionPage(BaseModel):
    items: list[SubmissionOut]
    total: int
    limit: int
    offset: int


class SubmissionPatch(BaseModel):
    is_saved: bool | None = None
    name: CompoundLabel | None = None


class SimilarRequest(SmilesRequest):
    min_similarity: float = Field(0.7, ge=0.0, le=1.0)
    limit: int = Field(10, ge=1, le=100)


class SimilarHit(BaseModel):
    similarity: float
    submission: SubmissionOut


# ---------- system ----------

class ModelStatus(BaseModel):
    loaded: bool
    model_version: str | None
    is_stub: bool | None
    error: str | None


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    database: str
    rdkit_cartridge: bool
    model: ModelStatus


class ModelInfo(BaseModel):
    model_version: str
    target: str
    is_stub: bool
    ensemble_size: int
    threshold: float
    conformal_significance: float | None
    applicability_min_similarity: float | None
    applicability_high_similarity: float | None
    featurizer: dict | None
    predicts_pic50: bool
    metrics: dict
