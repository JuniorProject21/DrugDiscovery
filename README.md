# DrugDiscovery
An application that predicts whether or not a compound contains active molecules that inhibit the PARP-1 enzyme.

The app is called **InhibitAI**: PARP-1 inhibitor activity prediction from SMILES. A senior project at the University of Sharjah.

```
Drug_discovery.ipynb, PARP1_Notebook_v2.ipynb   the team's research notebooks
android/    Kotlin + Jetpack Compose app        → presentation layer
backend/    Python FastAPI server               → application, domain and data access layers
            PostgreSQL (+ RDKit cartridge)      → database layer
deploy/     Docker Compose + Caddy (HTTPS) for running the backend on a server
notebooks/  the model notebook with its export step
report/     revised sections of Progress Report #2, its figures and the scripts that make them
```

This follows the architecture in Progress Report #1 (section 4.5, figure "Summary of the application
architecture"):

```
Android app ── HTTPS/JSON ──▶ Application layer   auth (JWT) · validate → check cache → infer → save
                              Domain layer        Chemistry Service   (RDKit: validate, standardise, depict)
                                                  Inference Service   (Morgan FP · Extra Trees · conformal · applicability domain)
                              Data access layer   User / Molecule / Submission / Prediction repositories
                              Database layer      PostgreSQL: users · molecules · submissions · predictions
```

## Quick start

```bash
# 1. Backend
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env            # set the database URL and a JWT secret
.venv/bin/uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000

# 2. App: open android/ in Android Studio and run it on an emulator
```

Details are in [backend/README.md](backend/README.md) and [android/README.md](android/README.md).
To put it online for teammates' phones, follow [DEPLOY.md](DEPLOY.md).

## The trained model

The trained model is included as `backend/models/parp1_bundle.joblib`. It was exported by
[notebooks/PARP1_model_colab_with_export.ipynb](notebooks/PARP1_model_colab_with_export.ipynb), the team's
`PARP1_model_colab` notebook with an export step added as section 12. To retrain, run that notebook in Colab,
copy the `parp1_bundle.joblib` it downloads to `backend/models/`, and install the scikit-learn and RDKit
versions it prints into the backend environment.

## What you still need to provide

- **A PostgreSQL database** for hosting. Use its URL in `backend/.env`. SQLite works for quick experiments.
