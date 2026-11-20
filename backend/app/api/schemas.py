"""JSON shapes exchanged with the Android app."""

from datetime import datetime
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, EmailStr, Field

from app.data.models import Prediction, Submission, User

SMILES_MAX = 1000


#: Report Table 6-5: "Allow only complex passwords (e.g. minimum 12 characters, mixing types)."
PASSWORD_MIN_LENGTH = 12

#: How many of the four kinds of character a new password has to use.
PASSWORD_MIN_KINDS = 3


def _new_password(value: str) -> str:
    """Checks a password being *set*. Signing in never re-checks, so tightening this
    rule locks nobody out of an account they already have."""
    if len(value.encode()) > 72:
        # bcrypt silently ignores anything past 72 bytes, so a longer one is refused
        # rather than quietly truncated.
        raise ValueError("Password is too long (maximum 72 bytes).")
    if len(value) < PASSWORD_MIN_LENGTH:
        raise ValueError(f"Password must be at least {PASSWORD_MIN_LENGTH} characters.")
    kinds = sum((
        any(c.islower() for c in value),
        any(c.isupper() for c in value),
        any(c.isdigit() for c in value),
        any(not c.isalnum() for c in value),
    ))
    if kinds < PASSWORD_MIN_KINDS:
        raise ValueError(
            "Password must mix at least three of: lower case, upper case, numbers, symbols."
        )
    return value


def _not_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("This can't be empty.")
    return value.strip()


NewPassword = Annotated[str, Field(min_length=1, max_length=72), AfterValidator(_new_password)]
PersonName = Annotated[str, Field(min_length=1, max_length=200), AfterValidator(_not_blank)]
CompoundLabel = Annotated[str, Field(min_length=1, max_length=50), AfterValidator(_not_blank)]


# ---------- auth ----------

PhoneNumber = Annotated[str, Field(min_length=4, max_length=24)]


class RegisterRequest(BaseModel):
    email: EmailStr
    password: NewPassword
    full_name: PersonName


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=72)


class PhoneSignUpRequest(BaseModel):
    phone: PhoneNumber = Field(description="Any readable form; the server normalises it to E.164")
    password: NewPassword
    full_name: PersonName


class PhoneCodeRequest(BaseModel):
    phone: PhoneNumber


class PhoneVerifyRequest(BaseModel):
    phone: PhoneNumber
    code: str = Field(pattern=r"^\d{6}$")


class PhoneLoginRequest(BaseModel):
    phone: PhoneNumber
    password: str = Field(min_length=1, max_length=72)


class PhonePasswordResetConfirm(BaseModel):
    phone: PhoneNumber
    code: str = Field(pattern=r"^\d{6}$")
    new_password: NewPassword


Role = Literal["student", "researcher", "supervisor", "other"]


class UserOut(BaseModel):
    id: int
    email: str | None = Field(description="Empty for accounts created with a phone number")
    phone: str | None = Field(None, description="In E.164 form, once verified")
    full_name: str
    institution: str | None = None
    role: Role | None = None
    research_area: str | None = None
    age: int | None = None
    created_at: datetime
    has_password: bool = Field(description="False for accounts that have only ever signed in with Google")
    google_linked: bool

    @classmethod
    def of(cls, user: User) -> "UserOut":
        return cls(id=user.id, email=user.email, phone=user.phone if user.phone_verified else None,
                   full_name=user.full_name, institution=user.institution,
                   role=user.role if user.role in ("student", "researcher", "supervisor", "other") else None,
                   research_area=user.research_area, age=user.age, created_at=user.created_at,
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
    """Only the fields actually sent are changed; an empty string clears an optional one."""

    full_name: PersonName | None = None
    institution: Annotated[str, Field(max_length=200)] | None = None
    role: Role | Literal[""] | None = None
    research_area: Annotated[str, Field(max_length=200)] | None = None
    age: Annotated[int, Field(ge=13, le=120)] | None = None


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
    highlight_atoms: list[int] = Field(
        default_factory=list, max_length=200,
        description="Atom indices in this SMILES to shade, used by the \"why this result\" panel")


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


# ---------- batch prediction ----------

BATCH_MAX = 500


class BatchRequest(BaseModel):
    smiles: list[str] = Field(min_length=1, max_length=BATCH_MAX,
                              description=f"Up to {BATCH_MAX} SMILES, in the order they were read from the file")
    # A row whose name column was blank sends null, so the list still lines up with `smiles`.
    labels: list[str | None] | None = Field(
        None, max_length=BATCH_MAX,
        description="Optional name for each row, from the file's name column; must match `smiles` in length")
    save: bool = Field(False, description="Add every valid row to the user's history as well")

    def rows(self) -> list[tuple[str, str | None]]:
        names = self.labels or [None] * len(self.smiles)
        if len(names) != len(self.smiles):
            raise ValueError("labels must have the same number of entries as smiles")
        return list(zip(self.smiles, names))


class BatchRow(BaseModel):
    index: int = Field(description="Row number in the uploaded file, starting at 0")
    input_smiles: str
    label_from_file: str | None
    ok: bool
    error: str | None = None
    name: str | None = Field(None, description="The compound's known name, or its XYZ-### label when saved")
    canonical_smiles: str | None = None
    formula: str | None = None
    molecular_weight: float | None = None
    prediction: PredictionOut | None = None
    submission_id: int | None = Field(None, description="Set when the row was saved to the history")

    @classmethod
    def of(cls, outcome, target: str) -> "BatchRow":
        if not outcome.ok:
            return cls(index=outcome.index, input_smiles=outcome.input_smiles,
                       label_from_file=outcome.label_from_file, ok=False, error=outcome.error)
        m = outcome.molecule
        return cls(
            index=outcome.index, input_smiles=outcome.input_smiles,
            label_from_file=outcome.label_from_file, ok=True,
            name=(outcome.submission.name if outcome.submission is not None
                  else m.common_name or outcome.label_from_file),
            canonical_smiles=m.canonical_smiles, formula=m.formula, molecular_weight=m.molecular_weight,
            prediction=PredictionOut.of(outcome.prediction, target),
            submission_id=outcome.submission.id if outcome.submission is not None else None,
        )


class BatchResponse(BaseModel):
    rows: list[BatchRow]
    total: int
    succeeded: int
    failed: int
    saved: bool


# ---------- explanation ----------

class FragmentOut(BaseModel):
    fragment: str = Field(description="The piece of chemistry, as a SMILES string")
    weight: float = Field(description="How much the score drops without it; positive pushed towards Active")
    radius: int
    atoms: list[int] = Field(description="Atom indices in `canonical_smiles`, for highlighting")
    occurrences: int


class ExplanationResponse(BaseModel):
    canonical_smiles: str
    score: float
    threshold: float
    label: Literal["active", "inactive", "uncertain"]
    model_version: str
    fragments: list[FragmentOut]


# ---------- "learn more" chat ----------

class ChatTurnIn(BaseModel):
    role: Literal["user", "assistant"]
    content: Annotated[str, Field(min_length=1, max_length=4000)]


class ChatRequest(SmilesRequest):
    """A question about one compound. The server looks the facts up itself, so the answer can
    only be grounded in what this server actually computed."""

    question: Annotated[str, Field(max_length=500)] | None = Field(
        None, description="Empty for the opening \"explain this result\" answer")
    name: Annotated[str, Field(max_length=100)] | None = Field(
        None, description="What the app calls this compound, used only as a label in the answer")
    history: list[ChatTurnIn] = Field(default_factory=list, max_length=16)


class ChatResponse(BaseModel):
    reply: str
    model: str = Field(description="The language model that answered")


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
