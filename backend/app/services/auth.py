"""Authentication / authorisation (application layer)."""

import hashlib
import hmac
import logging
import secrets
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.data.models import NO_PASSWORD, User
from app.data.repositories import PasswordResetRepository, UserRepository
from app.services.email import EmailSender
from app.services.google import GoogleIdentity

log = logging.getLogger(__name__)

# Compared against when the email is unknown, so failed logins take the same time either way.
_DUMMY_HASH = bcrypt.hashpw(b"not-a-real-password", bcrypt.gensalt()).decode()

RESET_CODE_LIFETIME = timedelta(minutes=15)
RESET_CODE_MAX_ATTEMPTS = 5


class AuthError(Exception):
    pass


class EmailTakenError(Exception):
    pass


class SignupNotAllowedError(Exception):
    pass


def signup_allowed(email: str, allowlist: list[str]) -> bool:
    """True when the allowlist is empty, names this email, or names its @domain."""
    email = email.lower()
    return not allowlist or any(email == entry or (entry.startswith("@") and email.endswith(entry))
                                for entry in allowlist)


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode(), password_hash.encode())
    except ValueError:  # also covers NO_PASSWORD, which isn't a bcrypt hash
        return False


class TokenService:
    """Issues and checks the bearer tokens the app sends with every request."""

    algorithm = "HS256"

    def __init__(self, secret: str, expiry_minutes: int):
        if not secret:
            secret = secrets.token_urlsafe(48)
            log.warning("INHIBITAI_JWT_SECRET is not set; using a random secret, so everyone is signed out "
                        "whenever the server restarts.")
        elif len(secret.encode()) < 32:
            raise ValueError("INHIBITAI_JWT_SECRET must be at least 32 bytes long.")
        self._secret = secret
        self.expiry = timedelta(minutes=expiry_minutes)

    def issue(self, user_id: int) -> tuple[str, int]:
        now = datetime.now(timezone.utc)
        payload = {"sub": str(user_id), "iat": now, "exp": now + self.expiry}
        return jwt.encode(payload, self._secret, algorithm=self.algorithm), int(self.expiry.total_seconds())

    def verify(self, token: str) -> int:
        try:
            payload = jwt.decode(token, self._secret, algorithms=[self.algorithm], options={"require": ["exp", "sub"]})
            return int(payload["sub"])
        except jwt.ExpiredSignatureError as exc:
            raise AuthError("Your session has expired. Please sign in again.") from exc
        except (jwt.InvalidTokenError, ValueError) as exc:
            raise AuthError("Invalid session. Please sign in again.") from exc

    def digest(self, message: str) -> str:
        """Keyed hash used to store reset codes: useless to anyone without the server secret."""
        return hmac.new(self._secret.encode(), message.encode(), hashlib.sha256).hexdigest()


class AuthService:
    def __init__(self, session: Session, tokens: TokenService, signup_allowlist: list[str] | None = None):
        self.session = session
        self.tokens = tokens
        self.signup_allowlist = signup_allowlist or []
        self.users = UserRepository(session)
        self.reset_codes = PasswordResetRepository(session)

    # ---------- email + password ----------

    def register(self, *, email: str, password: str, full_name: str) -> tuple[User, str, int]:
        if not signup_allowed(email, self.signup_allowlist):
            raise SignupNotAllowedError
        if self.users.get_by_email(email) is not None:
            raise EmailTakenError
        try:
            user = self.users.create(email=email, password_hash=hash_password(password), full_name=full_name.strip())
            self.session.commit()
        except IntegrityError as exc:  # registered concurrently
            self.session.rollback()
            raise EmailTakenError from exc
        return (user, *self.tokens.issue(user.id))

    def login(self, *, email: str, password: str) -> tuple[User, str, int]:
        user = self.users.get_by_email(email)
        if not verify_password(password, user.password_hash if user else _DUMMY_HASH) or user is None:
            if user is not None and not user.has_password:
                raise AuthError("This account signs in with Google. Use \"Continue with Google\", "
                                "or reset the password to add one.")
            raise AuthError("Incorrect email or password.")
        return (user, *self.tokens.issue(user.id))

    def user_from_token(self, token: str) -> User:
        user = self.users.get(self.tokens.verify(token))
        if user is None:
            raise AuthError("This account no longer exists.")
        return user

    # ---------- account details ----------

    def update_profile(self, user: User, *, full_name: str) -> User:
        user.full_name = full_name.strip()
        self.session.commit()
        return user

    def change_password(self, user: User, *, current_password: str | None, new_password: str) -> None:
        """Accounts that only use Google may set a first password without a current one."""
        if user.has_password and not verify_password(current_password or "", user.password_hash):
            raise AuthError("Your current password is incorrect.")
        user.password_hash = hash_password(new_password)
        self.session.commit()

    # ---------- password reset by emailed code ----------

    def request_password_reset(self, email: str, sender: EmailSender) -> None:
        """Emails a 6-digit code. Says nothing about whether the account exists."""
        user = self.users.get_by_email(email)
        if user is None:
            return
        code = f"{secrets.randbelow(1_000_000):06d}"
        self.reset_codes.replace_for_user(
            user.id, code_hash=self._code_hash(user.id, code),
            expires_at=datetime.now(timezone.utc) + RESET_CODE_LIFETIME,
        )
        self.session.commit()
        minutes = int(RESET_CODE_LIFETIME.total_seconds() // 60)
        sender.send(
            user.email,
            "Your InhibitAI password reset code",
            f"Hi {user.full_name},\n\n"
            f"Your code to reset your InhibitAI password is: {code}\n\n"
            f"It expires in {minutes} minutes. If you didn't ask to reset your password, you can ignore this email.\n",
        )

    def confirm_password_reset(self, *, email: str, code: str, new_password: str) -> tuple[User, str, int]:
        invalid = AuthError("That code is incorrect or has expired. Request a new one.")
        user = self.users.get_by_email(email)
        reset = self.reset_codes.active_for_user(user.id) if user else None
        if reset is None or reset.attempts >= RESET_CODE_MAX_ATTEMPTS:
            raise invalid
        if not hmac.compare_digest(reset.code_hash, self._code_hash(user.id, code)):
            reset.attempts += 1
            self.session.commit()
            raise invalid
        reset.used_at = datetime.now(timezone.utc)
        user.password_hash = hash_password(new_password)
        self.session.commit()
        return (user, *self.tokens.issue(user.id))

    def _code_hash(self, user_id: int, code: str) -> str:
        return self.tokens.digest(f"password-reset:{user_id}:{code}")

    # ---------- Google ----------

    def google_sign_in(self, identity: GoogleIdentity) -> tuple[User, str, int, bool]:
        """Signs in with a verified Google identity. Links it to an existing account with the same
        email, or creates one (subject to the sign-up allowlist). Returns (user, token, expiry, created)."""
        user = self.users.get_by_google_sub(identity.subject)
        created = False
        if user is None:
            user = self.users.get_by_email(identity.email)
            if user is not None:
                user.google_sub = identity.subject
            else:
                if not signup_allowed(identity.email, self.signup_allowlist):
                    raise SignupNotAllowedError
                name = (identity.name or identity.email.split("@")[0]).strip()[:200]
                user = self.users.create(email=identity.email, password_hash=NO_PASSWORD, full_name=name)
                user.google_sub = identity.subject
                created = True
            try:
                self.session.commit()
            except IntegrityError as exc:  # the same Google account signed in twice at once
                self.session.rollback()
                raise AuthError("Google sign-in failed, please try again.") from exc
        return (user, *self.tokens.issue(user.id), created)
