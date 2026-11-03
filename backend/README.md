# InhibitAI backend

FastAPI server behind the InhibitAI Android app. It implements the application, domain, data-access
and database layers from section 4.5 of the progress report. The app talks to it over HTTPS/JSON
(plain HTTP to your own computer in debug builds).

| Report layer | Code |
|---|---|
| Application layer: authentication, coordination | `app/api/` (routes, schemas, auth checks), `app/services/` (auth; validate → check cache → infer → save) |
| Domain layer: Chemistry Service | `app/domain/chemistry.py`: parse/validate SMILES, clean exactly like `prepare()` in the team's notebook (largest fragment, normalise, uncharge, canonical tautomer), 2D depiction |
| Domain layer: Inference Service | `app/domain/inference/`: Morgan featurisation (radius 3), Extra Trees, conformal "uncertain", similarity-based reliability, optional pIC50 |
| Data access layer | `app/data/repositories.py`: `UserRepository`, `MoleculeRepository`, `SubmissionRepository`, `PredictionRepository` |
| Database layer | `app/data/models.py`: `users`, `molecules`, `submissions`, `predictions` in PostgreSQL (+ RDKit cartridge) |

The Inference Service loads the model once, in the background at startup. Validating or drawing a
compound never touches the model, which is the reason for the Chemistry/Inference split in the report.

## Run it

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env        # then edit it (see below)
.venv/bin/uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
```

Check it at <http://localhost:8000/api/v1/health>. Interactive API docs are at <http://localhost:8000/docs>.

`--host 0.0.0.0` lets a real phone on the same Wi-Fi reach the server. The emulator reaches it at
`http://10.0.2.2:8000` either way.

### Database

Create a PostgreSQL database and put its URL in `.env`:

```bash
createdb inhibitai
```

```
INHIBITAI_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/inhibitai
```

Tables are created on startup. Without `INHIBITAI_DATABASE_URL`, the server uses a local SQLite file,
which is fine for trying things out.

**RDKit cartridge (optional).** When the `rdkit` extension is installed in PostgreSQL, the server
turns it on and adds two generated, GiST-indexed columns to `molecules`: `mol` (type `mol`) and
`mfp2` (type `bfp`). It then runs "similar compounds I've tested" searches in SQL. Without the
cartridge, the same search runs in Python, which is fine for one user's history. Homebrew's
PostgreSQL doesn't include the cartridge. The simplest way to get it is conda:

```bash
conda create -n inhibitai-db -c conda-forge rdkit-postgresql
```

### The model

The server loads one file, the **model bundle**, from `backend/models/parp1_bundle.joblib`. Make it
with section 12 of [`notebooks/PARP1_model_colab_with_export.ipynb`](../notebooks/PARP1_model_colab_with_export.ipynb)
(the team's `PARP1_model_colab` notebook plus an export step). Run the notebook in Colab, upload
[`scripts/export_model_bundle.py`](scripts/export_model_bundle.py) when section 12 asks for it, and
copy the downloaded file to `backend/models/`. The bundle holds:

- the 500-tree Extra Trees model and the balanced decision threshold
- the out-of-fold scores the conformal step compares against, so the model can answer **uncertain**
  exactly as `conformal()` does in the notebook (significance 0.20)
- the training SMILES, for the similarity check: reliability **High** at 0.7 or more, **Medium**
  from 0.5, **Low** below 0.5, as in `model/predict.py`
- a few raw SMILES with the fingerprints the notebook's `prepare()` produced for them

The server re-featurises those reference SMILES at startup and **refuses to serve** if its rows differ
from the notebook's. This catches the app preparing molecules differently from training, for example
tautomer canonicalisation switched off or a different fingerprint radius.

Install the same scikit-learn version as Colab (`pip install scikit-learn==<version>`). Pickled
models aren't guaranteed to load across versions, and the server logs a warning on a mismatch.

**Before the bundle exists:** with `INHIBITAI_ALLOW_STUB_MODEL=true`, the server answers with a
deterministic placeholder. Every placeholder result has model version `stub (no model loaded)`, and the
app shows "Placeholder result" on it. Without that setting and without a bundle, predictions return
503 rather than a made-up answer.

## API

All endpoints are under `/api/v1`. Everything except `health`, `auth/register` and `auth/login` needs
an `Authorization: Bearer <token>` header.

| Method & path | Purpose |
|---|---|
| `GET /health` | Database, cartridge and model status (doesn't load the model) |
| `POST /auth/register` | `{email, password, full_name}` → token + user |
| `POST /auth/login` | `{email, password}` → token + user |
| `POST /auth/google` | `{id_token}` from the app's Google button → token + user (creates or links the account) |
| `POST /auth/password-reset/request` | `{email}` → emails a 6-digit code (same answer whether or not the account exists) |
| `POST /auth/password-reset/confirm` | `{email, code, new_password}` → token + user |
| `GET /auth/me` | The signed-in user (`has_password`, `google_linked` included) |
| `PATCH /auth/me` | `{full_name}` |
| `POST /auth/change-password` | `{current_password, new_password}`; Google-only accounts may omit `current_password` |
| `GET /model` | Model version, threshold, conformal significance, featuriser, metrics |
| `POST /chem/validate` | `{smiles}` → valid?, readable error, standardised SMILES, formula, weight, PubChem name |
| `POST /chem/depict` | `{smiles, width, height, theme: light/dark, format: png/svg}` → image |
| `POST /predictions` | `{smiles}` → new history entry with its prediction (`cached: true` when the structure was predicted before) |
| `GET /predictions` | The user's history, newest first (`limit`, `offset`) |
| `GET /predictions/{id}` | One entry |
| `PATCH /predictions/{id}` | `{is_saved}` and/or `{name}` (rename) |
| `DELETE /predictions/{id}` | Remove from history |
| `POST /predictions/similar` | `{smiles, min_similarity, limit}` → the user's earlier entries with similar structures |

A prediction carries:

- `label`: the conformal verdict (`active` / `inactive` / `uncertain`)
- `score`: the fraction of trees voting active. Like the notebook says, it isn't a probability of being right.
- `threshold`, `confidence` (1 minus the other label's p-value) and the two conformal p-values
- `nearest_similarity`, `reliability` (`high` / `medium` / `low`) and `in_domain`
- `pic50` and `adme`, which stay null until those models exist
- `model_version`

An entry also carries the compound's `common_name`, `iupac_name` and `pubchem_cid` from PubChem, plus
`formula` and `molecular_weight`.

**Names.** PubChem is asked by InChIKey, a one-way hash of the structure, so the SMILES of an
unpublished compound never leaves the server.
- A known compound's entry is named after it, e.g. "Olaparib".
- Others get the next XYZ-### label; users can rename any entry.
- If PubChem is unreachable, the prediction still works and the name is tried again next time.
- `INHIBITAI_PUBCHEM_LOOKUP=false` switches lookups off.

**Caching.** Predictions are stored per standardised structure and model version. The same compound
entered as a salt, as another tautomer or by another user reuses the stored result. Exporting a new
model version invalidates the cache automatically.

**Database changes** are Alembic migrations in `app/data/migrations/`, applied automatically at
startup. A database from before migrations existed is recognised and upgraded in place. After
changing `app/data/models.py`, create a migration:

```bash
.venv/bin/alembic revision --autogenerate -m "describe the change"
```

A test fails if the models and migrations disagree.

**Password reset emails** need `INHIBITAI_SMTP_*` settings (see `.env.example` and DEPLOY.md). In
development without SMTP, the code is printed in the server's terminal. **Google sign-in** needs
`INHIBITAI_GOOGLE_CLIENT_IDS`; see section 5 of DEPLOY.md.

## Putting it online

See [../DEPLOY.md](../DEPLOY.md). The Docker image runs with `INHIBITAI_ENVIRONMENT=production`, which
refuses to start without a login secret, PostgreSQL and the model file. Set
`INHIBITAI_ALLOWED_SIGNUP_EMAILS` (emails and/or `@domains`) so only your team can create accounts.

## Tests

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
```

To also run every API test against PostgreSQL, set `INHIBITAI_TEST_POSTGRES_URL` to an empty test
database (it is wiped):

```bash
INHIBITAI_TEST_POSTGRES_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/inhibitai_test .venv/bin/python -m pytest
```

The tests train a tiny throwaway model on 16 molecules to exercise the code. It isn't a PARP-1 model.

## Not done yet

- **GIN blending.** Only the Extra Trees part of the hybrid is served. The report found the GIN's
  share changes results by less than the split-to-split noise. Adding it means one more model class in
  `app/domain/inference/service.py`, plus the PyTorch Geometric dependency.
- **ADME and pIC50.** The API fields exist. pIC50 works if you pass a fitted `regressor` to the
  export script. ADME stays `null`.
- **Sign in with Apple.** It needs a paid Apple developer account.
