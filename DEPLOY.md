# Putting InhibitAI online

This gets the backend onto a server with HTTPS and builds an app your teammates and supervisor can
install on their phones. It has three parts: the server, the app, and who's allowed to sign up.
Password-reset emails and Google sign-in are optional extras, set up in sections 4 and 5.

## What you need

- **A Linux server with at least 1 GB of RAM** (2 GB is comfortable), with ports 80 and 443 open to
  the internet and Docker installed. The model takes about 270 MB once loaded and peaks around
  450 MB while loading, so 512 MB plans are too small. Student cloud credits (for example through
  the GitHub Student Developer Pack) or a university machine are good options.
- **An address for it.** Either a domain you point at the server's IP, or the free sslip.io name
  built from the IP: `203-0-113-7.sslip.io` for `203.0.113.7`.
- **The model file**, `backend/models/parp1_bundle.joblib`.

## 1. Start the server

Get the project onto the server: clone it from your (private) GitHub repository, or copy the folder
with `scp`. The model file is git-ignored, so copy it separately:

```bash
scp backend/models/parp1_bundle.joblib USER@SERVER:inhibitai/backend/models/
```

On the server:

```bash
cd inhibitai/deploy
cp .env.example .env
nano .env                      # fill in the domain, two generated secrets, and who may sign up
docker compose up -d --build
```

This starts three containers: PostgreSQL, the API, and Caddy. Caddy fetches an HTTPS certificate
for your domain on its own. After about a minute, check:

```bash
curl https://YOUR_DOMAIN/api/v1/health
```

You should see `"status":"ok"` and `"loaded":true` for the model.

**If it doesn't come up:**

- `docker compose logs api`: in production mode the server refuses to start with unsafe settings
  and lists exactly what's missing.
- `docker compose logs caddy`: certificate problems almost always mean the domain doesn't point at
  this server yet, or port 80 is blocked.
- `docker compose up` fails with "port is already allocated", or another web server answers: something
  else on the machine (Apache, nginx, XAMPP) already uses port 80 or 443. Stop it, or use a machine
  that isn't running one.

### Alternative: a container hosting service

Any service that builds a Dockerfile works, for example Render, Railway, Fly.io or Azure Container Apps:

- Dockerfile `backend/Dockerfile`, build context `backend/`.
- **At least 1 GB of memory.**
- Add the service's PostgreSQL and set `INHIBITAI_DATABASE_URL` to its connection URL
  (`postgres://…` is fine).
- Set `INHIBITAI_JWT_SECRET` (generate it with `openssl rand -hex 32`) and
  `INHIBITAI_ALLOWED_SIGNUP_EMAILS`.
- Health check path: `/api/v1/health`.
- The model has to be inside the image. Commit it on purpose with
  `git add -f backend/models/parp1_bundle.joblib` (16 MB is fine for a private repository).

Hosted PostgreSQL usually doesn't include the RDKit cartridge. That's fine: "similar compounds"
searches then run in Python. On free tiers that sleep when idle, the first request after a pause can
take up to a minute. The app waits up to 90 seconds.

## 2. Build the app for your server

In `android/local.properties`, on the computer that builds the app:

```
inhibitai.apiBaseUrl=https://YOUR_DOMAIN/
```

Then build the APK, either with **Build → Build App Bundle(s) / APK(s) → Build APK(s)** in Android
Studio, or:

```bash
cd android && ./gradlew assembleDebug
```

The file is `android/app/build/outputs/apk/debug/app-debug.apk`. Send it to your teammates (Drive,
WhatsApp, email). On the phone, open it, allow installing apps from that source, and install.

- It needs Android 8.0 or newer. iPhones can't run it.
- An APK built on a different computer won't install over an earlier one (each computer signs with
  its own debug key), so uninstall the old app first.
- Publishing on Google Play would need a signed release build, which isn't set up.

## 3. Let people in

`INHIBITAI_ALLOWED_SIGNUP_EMAILS` decides who can create an account. It takes comma-separated
emails and/or whole domains:

```
INHIBITAI_ALLOWED_SIGNUP_EMAILS=@sharjah.ac.ae, supervisor@example.com
```

After changing it, run `docker compose up -d` to apply. Everyone else gets "Sign-up is limited to
invited email addresses" in the app. Existing accounts keep working.

## 4. Password reset emails (optional)

"Forgot password?" emails a 6-digit code. The server needs an SMTP account to send it. A Gmail
account works: turn on 2-Step Verification, create an **App password** (Google Account → Security →
App passwords), then add to `deploy/.env`:

```
INHIBITAI_SMTP_HOST=smtp.gmail.com
INHIBITAI_SMTP_PORT=587
INHIBITAI_SMTP_USERNAME=inhibitai.app@gmail.com
INHIBITAI_SMTP_PASSWORD=the-16-character-app-password
INHIBITAI_SMTP_FROM=InhibitAI <inhibitai.app@gmail.com>
```

Run `docker compose up -d` to apply. Any other SMTP provider works the same way; for port 465 add
`INHIBITAI_SMTP_SECURITY=ssl`.

- **Without SMTP**, a production server switches password reset off, and the app explains that.
- **On your own computer** (development mode), the code is printed in the backend's terminal instead
  of being emailed.

## 5. Google sign-in (optional)

"Continue with Google" needs OAuth clients that only your team can create, in
[Google Cloud Console](https://console.cloud.google.com/):

1. Create a project, e.g. "InhibitAI".
2. **APIs & Services → OAuth consent screen**: app name "InhibitAI", your support email, audience
   **External**. While the app is in *Testing*, only the Google accounts you add as **test users** can
   sign in, so add your teammates' and supervisor's addresses.
3. **Credentials → Create credentials → OAuth client ID → Web application**, named "InhibitAI server".
   No redirect URIs are needed. Copy its **client ID** (it ends in `.apps.googleusercontent.com`).
4. **Create credentials → OAuth client ID → Android**: package name `com.inhibitai.app`, plus the
   SHA-1 fingerprint of the key that signs the APK. For a debug APK, run this on the computer that
   builds it:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
   ```

   Each computer has its own debug key, so add one Android client per computer that builds APKs.
5. Put the **web** client ID from step 3 in two places:
   - the server's `deploy/.env`: `INHIBITAI_GOOGLE_CLIENT_IDS=1234-abc.apps.googleusercontent.com`,
     then run `docker compose up -d`;
   - `android/local.properties`: `inhibitai.googleWebClientId=1234-abc.apps.googleusercontent.com`,
     then rebuild the APK.

**Things to know:**

- **Google account on the phone:** each phone needs one added. On an emulator, use a system image
  with Google Play.
- **Allowlist:** Google sign-in follows the same email allowlist as sign-up.
- **Existing accounts:** an account with the same email is linked rather than duplicated.
- **"Developer console is not set up correctly":** the Android client's package name or SHA-1 doesn't
  match the APK.

## Keeping it running

| Task | How |
|---|---|
| New model | Export it from the notebook with a new `MODEL_VERSION`, copy it to `backend/models/parp1_bundle.joblib`, then `docker compose restart api`. If the notebook printed different scikit-learn/RDKit versions than the pins in `backend/requirements.txt`, update the pins and run `docker compose up -d --build`. |
| New code | `git pull && docker compose up -d --build`. Database changes apply automatically on start (migrations in `backend/app/data/migrations/`), and existing data is kept. |
| Logs | `docker compose logs -f api` |
| Backup | `docker compose exec db pg_dump -U inhibitai inhibitai > inhibitai-$(date +%F).sql` |
| Restore | On a fresh install, before the API has created its tables: `docker compose up -d db`, then `docker compose exec -T db psql -U inhibitai inhibitai < inhibitai-YYYY-MM-DD.sql`, then `docker compose up -d`. |

The compose file uses the standard PostgreSQL image, so similarity search runs in Python. To use the
RDKit cartridge, swap in a PostgreSQL image that has the `rdkit` extension; the server enables it on
its own.

## What production mode enforces

The Docker image runs with `INHIBITAI_ENVIRONMENT=production`. In that mode:

- The server won't start without a strong login secret, a PostgreSQL database and the model file,
  or if placeholder ("stub") predictions are switched on.
- Sign-up is limited to the allowlist. It logs a warning when the list is empty.
- Sign-in is rate limited: 10 attempts per account and 30 per phone address per 15 minutes; sign-up
  is 10 per address per hour.
- The API runs as a non-root user behind Caddy, which is the only container reachable from outside.
- Password reset needs SMTP (section 4). In production, reset codes are never written to the log.

Compound names come from PubChem, looked up by InChIKey: a one-way hash of the structure. So the
SMILES of an unpublished compound never leaves your server. Set `INHIBITAI_PUBCHEM_LOOKUP=false` to
switch lookups off entirely.

**Not covered yet:** monitoring and alerts.
