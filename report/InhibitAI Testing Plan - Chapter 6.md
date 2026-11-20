# InhibitAI Testing Plan — Chapter 6

Nov 20, 2026

## Where we are today

Two automated suites exist and both pass.

**Backend — 148 tests** (`backend/tests/`, pytest). 118 test functions; several are parametrised, so pytest collects 148 cases. By area: phone sign-up and login 18, accounts and profile 17, API routes 12, ADME 11, compound naming 10, assistant 10, inference 9, explanations 9, chemistry 8, batch screening 8, hosting and configuration 6.

**Android app — 40 tests** (`android/app/src/test/`, JUnit + Robolectric). CSV import 10, structure cache 10, undo-delete 6, batch cache 6, offline history 4, batch restore 4. These were added this week; before that the app had none.

**The model.** `ET v1.0`: an Extra Trees classifier over Morgan fingerprints (radius 3, 2048 bits), decision threshold 0.795, conformal prediction at 0.20 significance, trained on 4,942 compounds. On startup the server re-featurises 307 reference molecules and refuses to serve if any of them comes out different, which is a real regression check on the whole feature pipeline.

**Checked by hand on the emulator**, not automated: sign-up and login by phone, batch screening from a CSV, CSV export, offline history with the server stopped, the fragment panel, the assistant, PDF export, and the app surviving `am force-stop` with a finished batch run still on screen.

## What the chapter claims vs what the system does

Worth settling before the testing is written up, because a tester will check these against the running system. Each one is either a change to the code or a change to the sentence — both are fine, but they should agree.

| Chapter 6 says | The system does | Cheapest fix |
| --- | --- | --- |
| "the hybrid GNN-MLP model" (6.2, Table 6-5) | Extra Trees over Morgan fingerprints. There is no graph neural network and no MLP anywhere in the backend | Say Extra Trees in the report, and keep the GNN as future work |
| Passwords: minimum 12 characters, mixing types | 8 characters minimum, no complexity rule | Raise the rule in code (one line), or write 8 in the report |
| Session: Secure and HttpOnly cookie flags, expiry after minutes of inactivity | JWT bearer tokens in the Authorization header. No cookies exist, so those flags do not apply. A token lasts 7 days; a new one is issued at every login | Rewrite the row for tokens: new token per login, 7-day expiry, no cookies |
| Full disk encryption for the database | The SQLite file is not encrypted. Passwords are salted and hashed with bcrypt | Keep the bcrypt half, drop or qualify the disk-encryption half |
| Firewalls and strict computational timeouts against DoS | No rate limiting. There is a 500-compound cap per batch and a 5-minute client timeout | Add a simple per-IP rate limit, or describe the cap as the mitigation |
| Centralised logging, realtime alerts on failed logins | 11 log statements in the backend, none in the Android app, no audit trail | Add request and failed-login logging, or scope the claim to future work |
| Production credentials in a secure vault | Secrets live in `backend/.env` on the developer's machine | Describe `.env` for development and the vault as the production plan |

Two rows hold up as written: **SQL injection** (SQLAlchemy builds parameterised queries, and every SMILES passes RDKit validation before it reaches the logic layer) and **insecure API endpoints** (every route except sign-in requires a bearer token — there is already a test for this).

## The five test types, made concrete

Section 6.2 names five kinds of testing. Here is what each one means for this system, where it lives, and how far along it is.

| Type | For InhibitAI, this is | Where | Status |
| --- | --- | --- | --- |
| **Unit** | SMILES validation through RDKit, the conformal decision, ADME rules, fragment weights, phone-number normalisation, CSV parsing, the caches, the undo window | `backend/tests/`, `android/app/src/test/` | Done — 188 tests between the two, all passing |
| **Integration** | Two seams, not one: route to service to model inside the backend, and app to HTTP to backend between the two halves | Backend: covered through FastAPI's TestClient. App to backend: **not covered** — the Android tests use a fake server | Partly |
| **System** | The whole journey: sign in, enter a SMILES, see the prediction, save it, find it in history | Done by hand on the emulator every time something changes; nothing automated | Manual only |
| **Performance** | How long a prediction takes, cold and warm; a batch of 100 and of 500; the structure-drawing endpoint; whether the app stays responsive while it waits | Nothing measured yet | Not started |
| **Security** | The Table 6-5 mitigations, proven rather than asserted: a locked route refuses an anonymous call, a SQL-injection string in the SMILES field does nothing, a password never comes back in a response, an expired token is rejected | One auth test exists; the rest not written | Partly |

**On integration testing specifically.** The Android tests deliberately use a fake backend, which is what makes them fast and able to simulate a dead server — but it also means nothing checks that the app and the server still agree on the JSON. If the backend renames a field, every test on both sides still passes and the app breaks at runtime. One small suite that starts the real backend and calls it with the app's own models would close that, and it is the single most valuable test still missing.

## Splitting backend, frontend and model

The split is already in the folders, which makes it easier than it sounds.

**Backend — `backend/`.** FastAPI and the model. It owns accounts and sign-in, the database, the API contract, prediction, ADME, fragment explanations and the assistant. Its tests run with `pytest` and need no app and no emulator.

**Frontend — `android/`.** The Kotlin app. It owns screens, navigation, what is on screen when, the offline caches, CSV import and export, the PDF, notifications and accessibility. Its tests run with Gradle and need no server, because they use a fake one.

**The model — inside `backend/`, but a different kind of testing.** Everything above asks "does the code do what it says?". The model asks "is the answer any good?" — accuracy, precision and recall on the held-out set, whether the conformal prediction really covers what it claims, whether the applicability domain flags unfamiliar chemistry. That belongs in the evaluation part of Chapter 6, not the software-testing part, and it is the one piece that does not become more true by writing more unit tests.

**When you cannot tell which side a bug is on, reproduce it with `curl`.** If the server returns the wrong thing, it is backend. If the server is right and the screen is wrong, it is frontend. That one rule settles almost every case, and it is worth writing into the report as how the team triages.

**The seam between them belongs to both.** The app and the server have to agree on the JSON, and nothing currently checks that. Whoever takes it, it should be one person's job rather than nobody's.

A workable division for four people:

- **Backend API and security** — auth, routes, the Table 6-5 tests, rate limiting, logging.
- **Model evaluation** — metrics, conformal coverage, applicability domain, the numbers that go in the evaluation tables.
- **App** — unit tests for the screens' logic, then UI tests for the main journeys.
- **Integration, system and performance** — the contract test, the end-to-end runs, the timing table, and pulling it all into the chapter.

If you are on backend, your half is the first bullet plus the server side of the contract test.

## What is missing, in the order worth doing

1. **The contract test.** Start the real backend, call it with the app's own request and response models, and fail if a field has moved. Small, and it is the one gap where both suites can pass while the app is broken.
2. **Security tests for the Table 6-5 claims.** Four tests cover most of the table: an anonymous call to a locked route is refused; a SQL-injection string in the SMILES field is rejected by validation and changes nothing in the database; no response or log line ever contains a password; an expired token is refused. Written this way, the mitigation column stops being an assertion.
3. **Settle the two wording decisions** — the model description and the password rule. Minutes of work, but a tester checks the system against the sentence, so the sentence has to be the true one.
4. **Performance numbers.** A script that times one prediction cold and warm, a batch of 100, a batch of 500, and a structure drawing, repeated enough to report a median. That produces a table for the chapter, which reads far better than a paragraph saying performance was considered.
5. **Automate one system test.** A single UI test through the main journey — sign in, predict, save, find it in history — against a real backend. One is enough to claim the category honestly; more can follow.
6. **Restate the model evaluation** in the chapter: metrics on the held-out set, whether conformal coverage matches its stated level, and what the applicability domain catches. This is the part a supervisor is most likely to press on, and it is the part no amount of software testing answers.
7. **The small app fixes already identified** — keep the auth token out of Android backups, and produce a signed release build so the app can be installed without a laptop.

## Running the suites, and what each gives the report

**Backend**

```
cd backend && .venv/bin/python -m pytest -q
```

148 passed. For the chapter, the useful evidence is the summary line and the breakdown by area in the first section above.

**Android app**

```
cd android && ./gradlew testDebugUnitTest
```

40 passed. Gradle also writes an HTML report at `android/app/build/reports/tests/testDebugUnitTest/index.html`, listing every test by name — that page screenshots well as a figure, and the test names read as sentences on purpose.

**By hand, on the emulator.** Start the backend, start the emulator, install the app, then walk the journey: sign in, predict a known compound, open the result, check the fragments and ADME, run a CSV batch, export it, stop the backend and confirm history still shows with its structures. That list is worth writing into the chapter as the manual system-test script, with a pass column.

**One caution on numbers.** Do not write "188 tests" as if it were one suite. They are two suites of different kinds, and a reader who looks will see that. Report them separately — 148 backend, 40 app — and say what each covers.
