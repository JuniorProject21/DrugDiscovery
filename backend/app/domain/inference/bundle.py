"""The model bundle: one joblib file holding everything the Inference Service needs.

Create it at the end of the training notebook with scripts/export_model_bundle.py.
Only load bundles you created yourself: joblib files can run arbitrary code when loaded.
"""

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from app.domain.inference.conformal import MondrianConformal
from app.domain.inference.featurize import FeaturizerConfig

log = logging.getLogger(__name__)

FORMAT_VERSION = 1
POSITIVE_LABELS = (1, True, "active", "Active", "ACTIVE")


class BundleError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApplicabilityDomainSpec:
    """Similarity to the nearest training compound (Morgan radius 2), banded like model/predict.py:
    high >= high_similarity, medium >= min_similarity, low below it (outside the domain)."""

    train_smiles: list[str]
    min_similarity: float = 0.5
    high_similarity: float = 0.7
    radius: int = 2
    n_bits: int = 2048


@dataclass
class ModelBundle:
    model_version: str
    target: str
    featurizer: FeaturizerConfig
    classifiers: list[Any]
    positive_index: list[int]
    threshold: float
    conformal: MondrianConformal | None
    applicability_domain: ApplicabilityDomainSpec | None
    regressor: Any | None
    reference_smiles: list[str] = field(default_factory=list)
    reference_features: np.ndarray | None = None
    metrics: dict = field(default_factory=dict)
    library_versions: dict = field(default_factory=dict)


def load_bundle(path: Path) -> ModelBundle:
    try:
        raw = joblib.load(path)
    except Exception as exc:
        raise BundleError(f"Could not read model bundle {path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise BundleError(f"{path} is not a model bundle (expected a dict, got {type(raw).__name__}).")
    if raw.get("format_version") != FORMAT_VERSION:
        raise BundleError(
            f"Unsupported bundle format_version {raw.get('format_version')!r} (this server reads {FORMAT_VERSION})."
        )

    try:
        classifiers = raw["classifiers"]
        if not isinstance(classifiers, (list, tuple)):
            classifiers = [classifiers]
        if not classifiers:
            raise BundleError("The bundle contains no classifiers.")
        positive_index = [_positive_class_index(est) for est in classifiers]

        threshold = float(raw["threshold"])
        if not 0.0 <= threshold <= 1.0:
            raise BundleError(f"threshold must be between 0 and 1, got {threshold}")

        conformal = MondrianConformal(**raw["conformal"]) if raw.get("conformal") else None
        ad = ApplicabilityDomainSpec(**raw["applicability_domain"]) if raw.get("applicability_domain") else None
        reference = raw.get("reference") or {}
        bundle = ModelBundle(
            model_version=str(raw["model_version"]),
            target=str(raw.get("target", "PARP-1")),
            featurizer=FeaturizerConfig.from_dict(raw["featurizer"]),
            classifiers=list(classifiers),
            positive_index=positive_index,
            threshold=threshold,
            conformal=conformal,
            applicability_domain=ad,
            regressor=raw.get("regressor"),
            reference_smiles=list(reference.get("smiles", [])),
            reference_features=np.asarray(reference["features"]) if "features" in reference else None,
            metrics=dict(raw.get("metrics") or {}),
            library_versions=dict(raw.get("library_versions") or {}),
        )
    except KeyError as exc:
        raise BundleError(f"Model bundle is missing the required key {exc}.") from exc
    except (TypeError, ValueError) as exc:
        raise BundleError(f"Model bundle is invalid: {exc}") from exc

    _warn_on_library_mismatch(bundle.library_versions)
    return bundle


def _positive_class_index(estimator: Any) -> int:
    if not hasattr(estimator, "predict_proba"):
        raise BundleError(f"{type(estimator).__name__} has no predict_proba(); bundle classifiers must be probabilistic.")
    classes = list(getattr(estimator, "classes_", [0, 1]))
    for i, value in enumerate(classes):
        if value in POSITIVE_LABELS:
            return i
    raise BundleError(f"Could not find the 'active' class in classes_={classes}; label actives as 1.")


def _warn_on_library_mismatch(trained_with: dict) -> None:
    import rdkit
    import sklearn

    installed = {"scikit-learn": sklearn.__version__, "numpy": np.__version__, "rdkit": rdkit.__version__}
    for name, version in installed.items():
        expected = trained_with.get(name)
        if expected and expected != version:
            log.warning("Model bundle was built with %s %s but %s is installed; predictions may differ.",
                        name, expected, version)
