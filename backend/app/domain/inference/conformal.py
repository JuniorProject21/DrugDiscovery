"""Conformal prediction: lets the model answer "uncertain" instead of guessing (report, section 4.3.5)."""

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class ConformalDecision:
    label: str  # active | inactive | uncertain
    p_value_active: float
    p_value_inactive: float
    confidence: float


class MondrianConformal:
    """Class-conditional (Mondrian) inductive conformal predictor on top of the model's score.

    The calibration scores are out-of-fold scores of compounds with known labels, as in the team's
    notebook (CAL_ACTIVE / CAL_INACTIVE) and model/predict.py, and the p-values are computed the same way.
    The nonconformity of the label "active" for a compound scored p is 1 - p, and of "inactive" it is p.
    Each label's p-value compares the new compound against the held-out compounds that truly carry
    that label. A label is rejected when its p-value is at or below the significance level; the model
    only commits when exactly one label survives, otherwise it answers "uncertain".
    """

    def __init__(self, cal_scores, cal_labels, significance: float = 0.2):
        p = np.asarray(cal_scores, dtype=float).ravel()
        y = np.asarray(cal_labels).astype(int).ravel()
        if p.shape != y.shape:
            raise ValueError("cal_scores and cal_labels must have the same length")
        if not set(np.unique(y)) <= {0, 1}:
            raise ValueError("cal_labels must be 0 (inactive) or 1 (active)")
        if (y == 1).sum() == 0 or (y == 0).sum() == 0:
            raise ValueError("The calibration set needs both active and inactive compounds")
        if not 0 < significance < 1:
            raise ValueError("significance must be between 0 and 1")
        self.significance = float(significance)
        self._active_scores = np.sort(1.0 - p[y == 1])
        self._inactive_scores = np.sort(p[y == 0])

    @property
    def n_calibration(self) -> tuple[int, int]:
        return len(self._active_scores), len(self._inactive_scores)

    @staticmethod
    def _p_value(sorted_scores: np.ndarray, score: float) -> float:
        at_least_as_strange = len(sorted_scores) - np.searchsorted(sorted_scores, score, side="left")
        return float((at_least_as_strange + 1) / (len(sorted_scores) + 1))

    def decide(self, score: float) -> ConformalDecision:
        pa = self._p_value(self._active_scores, 1.0 - score)
        pi = self._p_value(self._inactive_scores, score)
        active_ok, inactive_ok = pa > self.significance, pi > self.significance
        if active_ok and not inactive_ok:
            return ConformalDecision("active", pa, pi, 1.0 - pi)
        if inactive_ok and not active_ok:
            return ConformalDecision("inactive", pa, pi, 1.0 - pa)
        if active_ok and inactive_ok:
            # Both labels are plausible: confidence is 1 - the second-largest p-value.
            return ConformalDecision("uncertain", pa, pi, 1.0 - min(pa, pi))
        # Both labels rejected: the compound looks like neither class. Report its credibility.
        return ConformalDecision("uncertain", pa, pi, max(pa, pi))


def threshold_confidence(score: float, threshold: float) -> float:
    """Confidence for threshold-only models: 0.5 at the decision threshold, 1.0 at either extreme.

    The threshold is tuned for balanced accuracy (report, section 4.3.3) rather than set at 0.5, so the
    raw score would understate confidence in "inactive" calls.
    """
    if score >= threshold:
        return 1.0 if threshold >= 1 else 0.5 + 0.5 * (score - threshold) / (1 - threshold)
    return 1.0 if threshold <= 0 else 0.5 + 0.5 * (threshold - score) / threshold
