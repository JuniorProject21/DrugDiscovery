import joblib
import numpy as np
import pytest

from app.domain.chemistry import ChemistryService
from app.domain.inference.bundle import BundleError, load_bundle
from app.domain.inference.conformal import MondrianConformal, threshold_confidence
from app.domain.inference.service import BundleModel, InferenceService, ModelUnavailableError

chem = ChemistryService()


def test_conformal_p_values_by_hand():
    # Calibration: actives scored 0.9, 0.8, 0.7; inactives scored 0.1, 0.2, 0.3.
    cp = MondrianConformal([0.9, 0.8, 0.7, 0.1, 0.2, 0.3], [1, 1, 1, 0, 0, 0], significance=0.3)
    d = cp.decide(0.85)
    # Active nonconformity 0.15; calibration actives have 0.1, 0.2, 0.3 -> two are >= 0.15.
    assert d.p_value_active == pytest.approx(3 / 4)
    # Inactive nonconformity 0.85; no calibration inactive scores that high.
    assert d.p_value_inactive == pytest.approx(1 / 4)
    assert d.label == "active"
    assert d.confidence == pytest.approx(0.75)


def test_conformal_says_uncertain_in_the_overlap():
    cp = MondrianConformal([0.9, 0.6, 0.5, 0.4, 0.2, 0.1], [1, 1, 1, 0, 0, 0], significance=0.2)
    assert cp.decide(0.5).label == "uncertain"


def test_conformal_rejects_bad_calibration():
    with pytest.raises(ValueError):
        MondrianConformal([0.9, 0.8], [1, 1])


def test_threshold_confidence_is_half_at_the_threshold():
    assert threshold_confidence(0.8, 0.8) == pytest.approx(0.5)
    assert threshold_confidence(1.0, 0.8) == pytest.approx(1.0)
    assert threshold_confidence(0.0, 0.8) == pytest.approx(1.0)
    assert threshold_confidence(0.4, 0.8) == pytest.approx(0.75)


def test_bundle_predicts_with_conformal_and_applicability_domain(toy_bundle):
    model = BundleModel.from_path(toy_bundle, chem)
    olaparib = chem.prepare("O=C1NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12").mol
    result = model.predict(olaparib)
    assert result.label == "active"
    assert 0.0 <= result.score <= 1.0
    assert result.p_value_active is not None and result.p_value_inactive is not None
    assert result.nearest_similarity == pytest.approx(1.0)
    assert result.in_domain is True
    assert result.reliability == "high"

    unrelated = chem.prepare("FC(F)(F)C(F)(F)C(F)(F)S(=O)(=O)O").mol
    far = model.predict(unrelated)
    assert far.in_domain is False and far.reliability == "low"


def test_reference_check_catches_featurisation_drift(toy_bundle, tmp_path):
    raw = joblib.load(toy_bundle)
    raw["reference"]["features"] = np.roll(raw["reference"]["features"], 1, axis=0)
    tampered = tmp_path / "tampered.joblib"
    joblib.dump(raw, tampered)
    with pytest.raises(BundleError, match="featurise differently"):
        BundleModel.from_path(tampered, chem)
    # ...unless explicitly skipped
    BundleModel.from_path(tampered, chem, skip_reference_check=True)


def test_unknown_bundle_format_is_rejected(tmp_path):
    path = tmp_path / "old.joblib"
    joblib.dump({"format_version": 99}, path)
    with pytest.raises(BundleError, match="format_version"):
        load_bundle(path)


def test_missing_bundle_without_stub_is_unavailable(tmp_path):
    service = InferenceService(tmp_path / "nope.joblib", chem, allow_stub=False)
    with pytest.raises(ModelUnavailableError, match="No model bundle"):
        service.load()
    assert service.status()["error"]


def test_stub_is_clearly_labelled(tmp_path):
    service = InferenceService(tmp_path / "nope.joblib", chem, allow_stub=True)
    version, result = service.predict(chem.prepare("NC(=O)c1ccccc1").mol)
    assert version.startswith("stub")
    assert service.status()["is_stub"] is True
    # deterministic
    assert service.predict(chem.prepare("NC(=O)c1ccccc1").mol)[1] == result
