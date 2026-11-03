"""Inference Service (domain layer): featurise a standardised molecule and run the model.

Self-contained so it could later run as its own server. The model loads once, either at startup
in the background or on the first prediction, never for validation or drawing requests.
"""

import hashlib
import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np
from rdkit import Chem, DataStructs
from rdkit.Chem import rdFingerprintGenerator

from app.domain.chemistry import ChemistryService, InvalidSmilesError
from app.domain.inference.bundle import BundleError, ModelBundle, load_bundle
from app.domain.inference.conformal import threshold_confidence
from app.domain.inference.featurize import Featurizer

log = logging.getLogger(__name__)


class ModelUnavailableError(RuntimeError):
    pass


@dataclass(frozen=True)
class InferenceResult:
    label: str  # active | inactive | uncertain
    score: float  # fraction of trees voting active (averaged over the ensemble); not a calibrated probability
    score_std: float | None
    confidence: float
    threshold: float
    p_value_active: float | None
    p_value_inactive: float | None
    nearest_similarity: float | None  # Tanimoto to the closest training compound
    in_domain: bool | None
    reliability: str | None  # high | medium | low
    pic50: float | None
    adme: dict | None


class InferenceModel(Protocol):
    model_version: str
    target: str
    is_stub: bool

    def predict(self, mol: Chem.Mol) -> InferenceResult: ...

    def info(self) -> dict: ...


class BundleModel:
    """The trained Extra Trees model (optionally an ensemble) loaded from a bundle file."""

    is_stub = False

    def __init__(self, bundle: ModelBundle):
        self.bundle = bundle
        self.model_version = bundle.model_version
        self.target = bundle.target
        self.featurizer = Featurizer(bundle.featurizer)
        self._ad_fps = []
        self._ad_generator = None
        if bundle.applicability_domain:
            ad = bundle.applicability_domain
            self._ad_generator = rdFingerprintGenerator.GetMorganGenerator(radius=ad.radius, fpSize=ad.n_bits)
            mols = [Chem.MolFromSmiles(s) for s in ad.train_smiles]
            self._ad_fps = [self._ad_generator.GetFingerprint(m) for m in mols if m is not None]
            if len(self._ad_fps) < len(mols):
                log.warning("%d training SMILES in the bundle could not be parsed and were skipped.",
                            len(mols) - len(self._ad_fps))

    @classmethod
    def from_path(cls, path: Path, chemistry: ChemistryService, *, skip_reference_check: bool = False):
        model = cls(load_bundle(path))
        if skip_reference_check:
            log.warning("Skipping the bundle reference check; features are not verified against training.")
        else:
            model.verify_reference_features(chemistry)
        return model

    def verify_reference_features(self, chemistry: ChemistryService) -> None:
        """Fail loudly if the server prepares molecules differently from the training notebook.

        The bundle stores raw SMILES with the feature rows the notebook computed for them; the
        server must reproduce those rows exactly through its own standardisation + featuriser.
        """
        b = self.bundle
        if not b.reference_smiles or b.reference_features is None:
            log.warning("Model bundle has no reference features; training/serving consistency is unverified.")
            return
        try:
            mols = [chemistry.standardise(chemistry.parse(s)) for s in b.reference_smiles]
        except InvalidSmilesError as exc:
            raise BundleError(f"A reference SMILES in the bundle is invalid: {exc}") from exc
        ours = self.featurizer.transform(mols).astype(np.float64)
        theirs = np.asarray(b.reference_features, dtype=np.float64)
        if ours.shape != theirs.shape:
            raise BundleError(f"Reference feature shape mismatch: server {ours.shape}, notebook {theirs.shape}. "
                              "Check the featurizer settings in the bundle.")
        mismatched = int((ours != theirs).any(axis=1).sum())
        if mismatched:
            raise BundleError(
                f"{mismatched}/{len(mols)} reference molecules featurise differently on the server than in "
                "training. The server's standardisation (INHIBITAI_TAUTOMER_CANONICALIZATION) and the bundle's "
                "featurizer settings must match the notebook."
            )
        log.info("Reference check passed: %d molecules featurise identically to training.", len(mols))

    def predict(self, mol: Chem.Mol) -> InferenceResult:
        b = self.bundle
        x = self.featurizer.transform([mol])
        probs = [float(est.predict_proba(x)[0, idx]) for est, idx in zip(b.classifiers, b.positive_index)]
        p = float(np.mean(probs))
        p_std = float(np.std(probs)) if len(probs) > 1 else None

        if b.conformal is not None:
            decision = b.conformal.decide(p)
            label, confidence = decision.label, decision.confidence
            pa, pi = decision.p_value_active, decision.p_value_inactive
        else:
            label = "active" if p >= b.threshold else "inactive"
            confidence, pa, pi = threshold_confidence(p, b.threshold), None, None

        nearest, in_domain, reliability = None, None, None
        if self._ad_fps:
            ad = b.applicability_domain
            fp = self._ad_generator.GetFingerprint(mol)
            nearest = float(max(DataStructs.BulkTanimotoSimilarity(fp, self._ad_fps)))
            in_domain = nearest >= ad.min_similarity
            reliability = "high" if nearest >= ad.high_similarity else "medium" if in_domain else "low"

        pic50 = float(b.regressor.predict(x)[0]) if b.regressor is not None else None
        return InferenceResult(
            label=label, score=p, score_std=p_std, confidence=float(confidence),
            threshold=b.threshold, p_value_active=pa, p_value_inactive=pi,
            nearest_similarity=nearest, in_domain=in_domain, reliability=reliability, pic50=pic50, adme=None,
        )

    def info(self) -> dict:
        b = self.bundle
        return {
            "model_version": b.model_version,
            "target": b.target,
            "is_stub": False,
            "ensemble_size": len(b.classifiers),
            "threshold": b.threshold,
            "conformal_significance": b.conformal.significance if b.conformal else None,
            "applicability_min_similarity": b.applicability_domain.min_similarity if b.applicability_domain else None,
            "applicability_high_similarity": b.applicability_domain.high_similarity if b.applicability_domain else None,
            "featurizer": b.featurizer.to_dict(),
            "predicts_pic50": b.regressor is not None,
            "metrics": b.metrics,
        }


class StubModel:
    """NOT A MODEL. A deterministic placeholder (a hash of the structure) that lets the app be
    developed before a trained bundle is exported. Only used with INHIBITAI_ALLOW_STUB_MODEL=true,
    and every result it produces carries the model_version below so it can't be mistaken for real."""

    is_stub = True
    model_version = "stub (no model loaded)"
    threshold = 0.5

    def __init__(self, target: str):
        self.target = target

    def predict(self, mol: Chem.Mol) -> InferenceResult:
        digest = hashlib.sha256(Chem.MolToSmiles(mol).encode()).digest()
        p = int.from_bytes(digest[:4], "big") / 2**32
        return InferenceResult(
            label="active" if p >= self.threshold else "inactive", score=p, score_std=None,
            confidence=threshold_confidence(p, self.threshold), threshold=self.threshold,
            p_value_active=None, p_value_inactive=None, nearest_similarity=None, in_domain=None,
            reliability=None, pic50=None, adme=None,
        )

    def info(self) -> dict:
        return {"model_version": self.model_version, "target": self.target, "is_stub": True,
                "ensemble_size": 0, "threshold": self.threshold, "conformal_significance": None,
                "applicability_min_similarity": None, "applicability_high_similarity": None, "featurizer": None,
                "predicts_pic50": False, "metrics": {}}


class InferenceService:
    def __init__(self, bundle_path: Path, chemistry: ChemistryService, *, allow_stub: bool = False,
                 skip_reference_check: bool = False, target: str = "PARP-1"):
        self.bundle_path = Path(bundle_path)
        self.chemistry = chemistry
        self.allow_stub = allow_stub
        self.skip_reference_check = skip_reference_check
        self.target = target
        self._model: InferenceModel | None = None
        self._error: str | None = None
        self._lock = threading.Lock()

    def load(self) -> InferenceModel:
        """Load the model once (thread-safe). Later calls return the same model or the same error."""
        with self._lock:
            if self._model is not None:
                return self._model
            if self._error is not None:
                raise ModelUnavailableError(self._error)
            try:
                self._model = self._load_model()
            except (BundleError, ModelUnavailableError) as exc:
                self._error = str(exc)
                log.error("Inference service unavailable: %s", exc)
                raise ModelUnavailableError(self._error) from exc
            log.info("Model ready: %s", self._model.model_version)
            return self._model

    def try_load(self) -> None:
        try:
            self.load()
        except ModelUnavailableError:
            pass  # already logged; requests will report it

    def _load_model(self) -> InferenceModel:
        if self.bundle_path.exists():
            log.info("Loading model bundle %s", self.bundle_path)
            return BundleModel.from_path(self.bundle_path, self.chemistry,
                                         skip_reference_check=self.skip_reference_check)
        if self.allow_stub:
            log.warning("No model bundle at %s. Using the STUB model: predictions are placeholders, not science.",
                        self.bundle_path)
            return StubModel(self.target)
        raise ModelUnavailableError(
            f"No model bundle at {self.bundle_path}. Export one with scripts/export_model_bundle.py, "
            "or set INHIBITAI_ALLOW_STUB_MODEL=true to develop against a placeholder."
        )

    def predict(self, mol: Chem.Mol) -> tuple[str, InferenceResult]:
        model = self.load()
        return model.model_version, model.predict(mol)

    def status(self) -> dict:
        """Current state without triggering a load."""
        model = self._model
        return {
            "loaded": model is not None,
            "model_version": model.model_version if model else None,
            "is_stub": model.is_stub if model else None,
            "error": self._error,
        }
