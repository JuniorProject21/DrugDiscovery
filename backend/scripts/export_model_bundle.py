"""Package the trained PARP-1 model into the bundle file the backend loads.

Copy this file next to your training notebook (e.g. upload it to Colab) and call
`export_bundle(...)` at the end of training. It only needs numpy, joblib and scikit-learn,
so it does not depend on the backend code.

The ready-made way is section 12 of notebooks/PARP1_model_colab_with_export.ipynb, which calls this with
the variables from the team's PARP1_model_colab notebook. In short:

    from export_model_bundle import export_bundle

    export_bundle(
        "parp1_bundle.joblib",
        model_version="ET v1.0",           # change it whenever the model changes (it keys the result cache)
        classifiers=final,                 # the fitted ExtraTreesClassifier (or a list of models to average)
        threshold=cutoff,                  # balanced decision threshold from the pooled out-of-fold scores
        featurizer={"kind": "morgan", "radius": 3, "n_bits": 2048},   # exactly what training used
        cal_scores=oof[seen],              # out-of-fold scores: what the conformal step compares against
        cal_labels=y[seen],                # 1 = active, 0 = inactive, same compounds as cal_scores
        significance=EPS,                  # conformal error rate (0.20 = wrong at most 20% of decided compounds)
        train_smiles=d.smiles.tolist(),    # training SMILES, for the similarity / reliability check
        reference_smiles=ref_smiles,       # some RAW SMILES (salts, tautomers, training compounds)...
        reference_features=ref_X,          # ...and the rows prepare() + morgan3 gave for them in the notebook
        metrics={"scaffold_mcc": 0.712, "random_mcc": 0.778},
    )

Then copy parp1_bundle.joblib to backend/models/ (or point INHIBITAI_MODEL_BUNDLE_PATH at it).

On startup the server re-featurises `reference_smiles` with its own standardisation and featuriser
and refuses to serve if the rows differ from `reference_features`. That catches the most common
deployment bug: the app preparing molecules differently from the notebook that trained the model.
"""

from datetime import datetime, timezone

import joblib
import numpy as np

FORMAT_VERSION = 1
FEATURIZER_KINDS = ("morgan", "morgan_count", "fcfp", "fcfp_count", "maccs")


def export_bundle(
    path,
    *,
    model_version,
    classifiers,
    threshold,
    featurizer,
    cal_scores=None,
    cal_labels=None,
    significance=0.20,
    train_smiles=None,
    applicability_min_similarity=0.5,
    applicability_high_similarity=0.7,
    applicability_radius=2,
    applicability_n_bits=2048,
    reference_smiles=None,
    reference_features=None,
    regressor=None,
    metrics=None,
    target="PARP-1",
):
    classifiers = list(classifiers) if isinstance(classifiers, (list, tuple)) else [classifiers]
    for est in classifiers:
        if not hasattr(est, "predict_proba"):
            raise TypeError(f"{type(est).__name__} has no predict_proba()")
    if featurizer.get("kind") not in FEATURIZER_KINDS:
        raise ValueError(f"featurizer['kind'] must be one of {FEATURIZER_KINDS}")
    if not 0.0 <= float(threshold) <= 1.0:
        raise ValueError("threshold must be between 0 and 1")

    conformal = None
    if cal_scores is not None or cal_labels is not None:
        cal_scores = np.asarray(cal_scores, dtype=float).ravel()
        cal_labels = np.asarray(cal_labels).astype(int).ravel()
        if cal_scores.shape != cal_labels.shape:
            raise ValueError("cal_scores and cal_labels must have the same length")
        if np.isnan(cal_scores).any():
            raise ValueError("cal_scores contains NaN; pass only compounds that got an out-of-fold score (oof[seen])")
        if set(np.unique(cal_labels)) != {0, 1}:
            raise ValueError("cal_labels must contain both 0 (inactive) and 1 (active)")
        conformal = {"cal_scores": cal_scores, "cal_labels": cal_labels, "significance": float(significance)}

    applicability_domain = None
    if train_smiles is not None:
        applicability_domain = {
            "train_smiles": [str(s) for s in train_smiles],
            "min_similarity": float(applicability_min_similarity),
            "high_similarity": float(applicability_high_similarity),
            "radius": int(applicability_radius),
            "n_bits": int(applicability_n_bits),
        }

    reference = None
    if reference_smiles is not None:
        features = np.asarray(reference_features)
        if features.ndim != 2 or features.shape[0] != len(reference_smiles):
            raise ValueError("reference_features must have one row per reference SMILES")
        reference = {"smiles": [str(s) for s in reference_smiles], "features": features}

    bundle = {
        "format_version": FORMAT_VERSION,
        "model_version": str(model_version),
        "target": target,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "library_versions": _library_versions(),
        "featurizer": dict(featurizer),
        "classifiers": classifiers,
        "threshold": float(threshold),
        "conformal": conformal,
        "applicability_domain": applicability_domain,
        "regressor": regressor,
        "reference": reference,
        "metrics": dict(metrics or {}),
    }
    joblib.dump(bundle, path, compress=3)
    print(f"Saved {path}: {len(classifiers)} classifier(s), conformal={'yes' if conformal else 'no'}, "
          f"applicability domain={'yes' if applicability_domain else 'no'}, "
          f"reference check={'yes' if reference else 'NO (recommended!)'}")
    return path


def _library_versions():
    versions = {"numpy": np.__version__}
    try:
        import sklearn
        versions["scikit-learn"] = sklearn.__version__
    except ImportError:
        pass
    try:
        import rdkit
        versions["rdkit"] = rdkit.__version__
    except ImportError:
        pass
    return versions
