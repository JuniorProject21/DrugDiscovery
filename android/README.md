# InhibitAI: Android app (Kotlin + Jetpack Compose)

The presentation layer of InhibitAI. It shows screens and sends everything else to the backend in
`../backend` over HTTPS/JSON: sign-in, SMILES validation, 2D drawings, predictions, history.

## Open & run

1. Start the backend first (see `../backend/README.md`).
2. In Android Studio: **File → Open…** and pick this `android` folder. Let Gradle sync.
3. Run on an emulator. The app reaches the backend at `http://10.0.2.2:8000/`, which is your computer
   as seen from the emulator.

**Teammates' phones:** put the backend online and build the APK for it, as described in
[../DEPLOY.md](../DEPLOY.md).

**Your own phone, same Wi-Fi:** put your computer's Wi-Fi IP in `local.properties` (next to this
README) and run the backend with `--host 0.0.0.0`:

```
inhibitai.apiBaseUrl=http://192.168.1.23:8000/
```

Debug builds allow plain `http://` (see `app/src/debug/AndroidManifest.xml`). Release builds need an
`https://` server.

**Gradle JDK:** the project uses Gradle 8.9, which runs on JDK 17–22. If Android Studio's bundled
JDK is newer, sync fails with only a version number as the error. Fix it in **Settings → Build Tools →
Gradle → Gradle JDK** (pick a 17 or 21), or accept Android Studio's offer to upgrade Gradle/AGP.

## How it talks to the backend

| File | Role |
|---|---|
| `data/remote/InhibitApi.kt` | Retrofit interface for the REST API, plus the OkHttp client that adds the bearer token |
| `data/remote/ApiModels.kt` | JSON request/response classes (kotlinx.serialization) |
| `data/InhibitRepository.kt` | The app's only gateway to the backend. Turns HTTP errors into readable messages and caches structure images |
| `data/SessionStore.kt` | Keeps the user signed in between launches (DataStore) |
| `ui/InhibitViewModel.kt` | Screen state: auth, history, live SMILES checking, optimistic save/delete |
| `ui/components/StructureImage.kt` | Shows RDKit drawings from `POST /chem/depict` at the exact pixel size needed |

## Screens

| Screen | File | Backed by |
|---|---|---|
| Onboarding | `ui/screens/AuthScreens.kt` → `OnboardingScreen` | |
| Login / Sign up (email or Google) | `ui/screens/AuthScreens.kt` → `LoginScreen`, `SignUpScreen` | `/auth/login`, `/auth/register`, `/auth/google` |
| Forgot password (emailed 6-digit code) | `ui/screens/AuthScreens.kt` → `ForgotPasswordScreen` | `/auth/password-reset/*` |
| Home | `ui/screens/HomeScreen.kt` | `/predictions` |
| New Prediction (name shown as you type) | `ui/screens/PredictScreen.kt` | `/chem/validate` while typing, `/chem/depict`, `/predictions` |
| Prediction Result: name, IUPAC name, PubChem link, rename, similar compounds, PDF export | `ui/screens/ResultScreen.kt`, `ui/export/PdfReport.kt` | the saved prediction, `PATCH /predictions/{id}`, `/predictions/similar` |
| History (search, filters, sort, save, delete) | `ui/screens/HistoryScreen.kt` | `/predictions`, `PATCH` / `DELETE /predictions/{id}` |
| Insights (distribution + last 7 days) | `ui/screens/InsightsScreen.kt` | computed from the history |
| Profile, with Model / Help / About | `ui/screens/ProfileScreen.kt` | `/auth/me`, `/model` |
| Account details: name, change or set a password | `ui/screens/AccountScreen.kt` | `PATCH /auth/me`, `/auth/change-password` |

How results are shown:

- **Verdicts:** the model can answer **Uncertain** as well as Active / Inactive, and it rates each
  result's **reliability** by similarity to its training chemistry. The result screen explains both
  in a yellow note, and History has an "Uncertain" filter.
- **Names:** compound names come from PubChem. Unpublished compounds keep their XYZ-### label until you
  rename them.

## Google sign-in

The Google button works once the app knows your OAuth **web** client ID. Put it in `local.properties`:

```
inhibitai.googleWebClientId=1234-abc.apps.googleusercontent.com
```

You also need the matching server setting and an Android OAuth client; see section 5 of
[../DEPLOY.md](../DEPLOY.md). Without the ID, the button says Google sign-in isn't set up yet.

## Not available yet

- **Sign in with Apple:** needs a paid Apple developer account.
- **ADME card:** shows "not available yet" until the backend has an ADME model.
- **Notifications:** there are none; the bell just says so.
