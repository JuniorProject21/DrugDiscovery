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
from app.data.repositories import PasswordResetRepository, PhoneVerificationRepository, UserRepository
from app.services.email import EmailSender
from app.services.google import GoogleIdentity
from app.services.phone import SmsSender

log = logging.getLogger(__name__)

# Compared against when the email is unknown, so failed logins take the same time either way.
_DUMMY_HASH = bcrypt.hashpw(b"not-a-real-password", bcrypt.gensalt()).decode()

#: How stale `users.last_seen_at` may get before a request writes it again.
_LAST_SEEN_RESOLUTION = timedelta(minutes=1)

RESET_CODE_LIFETIME = timedelta(minutes=15)
RESET_CODE_MAX_ATTEMPTS = 5
VERIFY_CODE_LIFETIME = timedelta(minutes=15)
VERIFY_CODE_MAX_ATTEMPTS = 5


class AuthError(Exception):
    pass


class EmailTakenError(Exception):
    pass


class SignupNotAllowedError(Exception):
    pass


class PhoneTakenError(Exception):
    pass


class PhoneNotVerifiedError(Exception):
    """The number has an account, but the sign-up code was never entered."""


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
    def __init__(self, session: Session, tokens: TokenService, signup_allowlist: list[str] | None = None,
                 idle_window: timedelta | None = None):
        self.session = session
        self.tokens = tokens
        self.signup_allowlist = signup_allowlist or []
        #: How long an account may go without a request before its token stops working.
        #: None switches the check off.
        self.idle_window = idle_window
        self.users = UserRepository(session)
        self.reset_codes = PasswordResetRepository(session)
        self.phone_codes = PhoneVerificationRepository(session)

    # ---------- email + password ----------

    def register(self, *, email: str, password: str, full_name: str) -> tuple[User, str, int]:
        if not signup_allowed(email, self.signup_allowlist):
            raise SignupNotAllowedError
        if self.users.get_by_email(email) is not None:
            raise EmailTakenError
        try:
            user = self.users.create(email=email, password_hash=hash_password(password),
                                     full_name=full_name.strip())
            self.session.commit()
        except IntegrityError as exc:  # registered concurrently
            self.session.rollback()
            raise EmailTakenError from exc
        return (user, *self._issue_token(user))

    def login(self, *, email: str, password: str) -> tuple[User, str, int]:
        return self._sign_in(self.users.get_by_email(email), password, "Incorrect email or password.")

    def _sign_in(self, user: User | None, password: str, wrong: str) -> tuple[User, str, int]:
        """Shared by email and phone sign-in. Always does the bcrypt work, so a wrong identifier
        takes the same time as a wrong password and the API doesn't reveal which accounts exist."""
        if not verify_password(password, user.password_hash if user else _DUMMY_HASH) or user is None:
            if user is not None and not user.has_password:
                raise AuthError("This account signs in with Google. Use \"Continue with Google\", "
                                "or reset the password to add one.")
            raise AuthError(wrong)
        return (user, *self._issue_token(user))

    # ---------- phone number ----------

    def start_phone_signup(self, *, phone: str, full_name: str, password: str,
                           sender: SmsSender) -> tuple[User, bool]:
        """Create (or re-use) an unverified account for `phone` and text it a 6-digit code.

        Re-running this for a number that was never verified replaces the earlier code and takes
        the new name and password, so someone who closed the app mid-sign-up can simply start over.
        Returns (user, created).
        """
        if not signup_allowed(phone, self.signup_allowlist):
            raise SignupNotAllowedError
        user = self.users.get_by_phone(phone)
        created = False
        if user is None:
            user = self.users.create(phone=phone, password_hash=hash_password(password),
                                     full_name=full_name.strip())
            created = True
        elif user.phone_verified:
            raise PhoneTakenError
        else:
            user.full_name = full_name.strip()
            user.password_hash = hash_password(password)
        try:
            self.session.flush()
        except IntegrityError as exc:  # the same number started signing up twice at once
            self.session.rollback()
            raise PhoneTakenError from exc
        self._send_phone_code(user, sender)
        return user, created

    def resend_phone_code(self, *, phone: str, sender: SmsSender) -> None:
        """Send a fresh code. Says nothing about whether the number has an account."""
        user = self.users.get_by_phone(phone)
        if user is None or user.phone_verified:
            return
        self._send_phone_code(user, sender)

    def verify_phone(self, *, phone: str, code: str) -> tuple[User, str, int]:
        invalid = AuthError("That code is incorrect or has expired. Request a new one.")
        user = self.users.get_by_phone(phone)
        if user is None:
            raise invalid
        if user.phone_verified:
            raise AuthError("This number is already verified. Please sign in.")
        entry = self.phone_codes.active_for_user(user.id)
        if entry is None or entry.attempts >= VERIFY_CODE_MAX_ATTEMPTS:
            raise invalid
        if not hmac.compare_digest(entry.code_hash, self._phone_code_hash(user.id, code)):
            entry.attempts += 1
            self.session.commit()
            raise invalid
        now = datetime.now(timezone.utc)
        entry.used_at = now
        user.phone_verified_at = now
        self.session.commit()
        return (user, *self._issue_token(user))

    def login_with_phone(self, *, phone: str, password: str) -> tuple[User, str, int]:
        user = self.users.get_by_phone(phone)
        if user is not None and not user.phone_verified:
            raise PhoneNotVerifiedError
        return self._sign_in(user, password, "Incorrect phone number or password.")

    def _send_phone_code(self, user: User, sender: SmsSender) -> None:
        code = f"{secrets.randbelow(1_000_000):06d}"
        self.phone_codes.replace_for_user(
            user.id, code_hash=self._phone_code_hash(user.id, code),
            expires_at=datetime.now(timezone.utc) + VERIFY_CODE_LIFETIME,
        )
        self.session.commit()
        minutes = int(VERIFY_CODE_LIFETIME.total_seconds() // 60)
        sender.send(user.phone, f"{code} is your InhibitAI verification code. "
                                f"It expires in {minutes} minutes.")

    def _phone_code_hash(self, user_id: int, code: str) -> str:
        return self.tokens.digest(f"phone-verify:{user_id}:{code}")

    def _issue_token(self, user: User) -> tuple[str, int]:
        """Issues a token and starts the idle clock. Every sign-in mints a fresh token."""
        user.last_seen_at = datetime.now(timezone.utc)
        self.session.commit()
        return self.tokens.issue(user.id)

    def user_from_token(self, token: str) -> User:
        user = self.users.get(self.tokens.verify(token))
        if user is None:
            raise AuthError("This account no longer exists.")
        self._check_not_idle(user)
        return user

    def _check_not_idle(self, user: User) -> None:
        """Ends a session that has been sitting unused, and records this request as activity.

        A token that is still within its own expiry is refused once the account has made no
        request for [idle_window]. The clock is the account's last request rather than anything
        in the token, so the user has to sign in again, which is what Table 6-5 asks for.
        """
        now = datetime.now(timezone.utc)
        if self.idle_window is not None and user.last_seen_at is not None:
            if now - user.last_seen_at > self.idle_window:
                # Not cleared: the next sign-in overwrites it anyway, and keeping it makes
                # "when did this account go quiet" answerable.
                raise AuthError("Your session timed out. Please sign in again.")
        # Written at most once a minute: every request would be a write for no added accuracy.
        if user.last_seen_at is None or (now - user.last_seen_at) > _LAST_SEEN_RESOLUTION:
            user.last_seen_at = now
            self.session.commit()

    # ---------- account details ----------

    def update_profile(self, user: User, **fields) -> User:
        """Updates only the fields that were sent. An empty string clears an optional one, so the
        user can take a detail back off their profile."""
        for name, value in fields.items():
            if name == "full_name":
                if value and value.strip():
                    user.full_name = value.strip()
            elif name == "age":
                user.age = value  # null clears it; the schema checks the range
            else:
                text = (value or "").strip() if isinstance(value, str) else value
                setattr(user, name, text or None)
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
        if user is None:
            raise invalid
        return self._use_reset_code(user, code, new_password, invalid)

    # ---------- password reset by texted code ----------

    def request_phone_password_reset(self, phone: str, sender: SmsSender) -> None:
        """Texts a 6-digit reset code. Says nothing about whether the number has an account.

        An account whose number was never verified is left alone: it still has an unfinished
        sign-up to complete, and resetting through it would be a way around that step.
        """
        user = self.users.get_by_phone(phone)
        if user is None or not user.phone_verified:
            return
        code = f"{secrets.randbelow(1_000_000):06d}"
        self.reset_codes.replace_for_user(
            user.id, code_hash=self._code_hash(user.id, code),
            expires_at=datetime.now(timezone.utc) + RESET_CODE_LIFETIME,
        )
        self.session.commit()
        minutes = int(RESET_CODE_LIFETIME.total_seconds() // 60)
        sender.send(user.phone, f"{code} is your InhibitAI password reset code. "
                                f"It expires in {minutes} minutes.")

    def confirm_phone_password_reset(self, *, phone: str, code: str,
                                     new_password: str) -> tuple[User, str, int]:
        """Same checks as the emailed reset, against the number instead of the address."""
        invalid = AuthError("That code is incorrect or has expired. Request a new one.")
        user = self.users.get_by_phone(phone)
        if user is None or not user.phone_verified:
            raise invalid
        return self._use_reset_code(user, code, new_password, invalid)

    def _use_reset_code(self, user: User, code: str, new_password: str,
                        invalid: AuthError) -> tuple[User, str, int]:
        reset = self.reset_codes.active_for_user(user.id)
        if reset is None or reset.attempts >= RESET_CODE_MAX_ATTEMPTS:
            raise invalid
        if not hmac.compare_digest(reset.code_hash, self._code_hash(user.id, code)):
            reset.attempts += 1
            self.session.commit()
            raise invalid
        reset.used_at = datetime.now(timezone.utc)
        user.password_hash = hash_password(new_password)
        self.session.commit()
        return (user, *self._issue_token(user))

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
        return (user, *self._issue_token(user), created)
