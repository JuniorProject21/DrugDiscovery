from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.api.deps import Container, get_auth_service, get_container, get_current_user
from app.api.schemas import (
    GoogleSignInRequest,
    LoginRequest,
    MessageResponse,
    PasswordChange,
    PasswordResetConfirm,
    PasswordResetRequest,
    PhoneCodeRequest,
    PhoneLoginRequest,
    PhonePasswordResetConfirm,
    PhoneSignUpRequest,
    PhoneVerifyRequest,
    ProfileUpdate,
    RegisterRequest,
    TokenResponse,
    UserOut,
)
from app.data.models import User
from app.services.auth import (
    AuthError,
    AuthService,
    EmailTakenError,
    PhoneNotVerifiedError,
    PhoneTakenError,
    SignupNotAllowedError,
)
from app.services.google import InvalidGoogleToken
from app.services.phone import InvalidPhoneError, mask_phone, normalise_phone
from app.services.ratelimit import RateLimiter

SIGNUP_NOT_ALLOWED = "Sign-up is limited to invited email addresses. Ask the InhibitAI team to add yours."
# The allowlist holds email addresses and domains, so when one is in force phone sign-up cannot be
# matched against it and is refused rather than quietly let through.
SIGNUP_NOT_ALLOWED_PHONE = ("Sign-up is limited to invited email addresses, so please sign up with "
                            "your email instead of a phone number.")

router = APIRouter(prefix="/auth", tags=["auth"])


def _limit(limiter: RateLimiter, key: str) -> None:
    if not limiter.allow(key):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS,
                            "Too many attempts. Please wait a few minutes and try again.",
                            headers={"Retry-After": str(limiter.retry_after(key))})


def _client_ip(request: Request) -> str:
    # Behind a proxy, uvicorn's --proxy-headers makes this the phone's address, not the proxy's.
    return request.client.host if request.client else "unknown"


@router.post("/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
def register(body: RegisterRequest, request: Request, auth: AuthService = Depends(get_auth_service),
             container: Container = Depends(get_container)):
    _limit(container.auth_limits.signup_per_ip, _client_ip(request))
    try:
        user, token, expires_in = auth.register(email=body.email, password=body.password, full_name=body.full_name)
    except SignupNotAllowedError:
        raise HTTPException(status.HTTP_403_FORBIDDEN, SIGNUP_NOT_ALLOWED)
    except EmailTakenError:
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists.")
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user))


@router.post("/login", response_model=TokenResponse)
def login(body: LoginRequest, request: Request, auth: AuthService = Depends(get_auth_service),
          container: Container = Depends(get_container)):
    _limit(container.auth_limits.login_per_ip, _client_ip(request))
    _limit(container.auth_limits.login_per_email, body.email.lower())
    try:
        user, token, expires_in = auth.login(email=body.email, password=body.password)
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user))


# ---------- phone number (progress report figures 11-3 and 11-5) ----------

def _phone(value: str) -> str:
    try:
        return normalise_phone(value)
    except InvalidPhoneError as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc


@router.post("/phone/sign-up", response_model=MessageResponse, status_code=status.HTTP_202_ACCEPTED)
def phone_sign_up(body: PhoneSignUpRequest, request: Request, auth: AuthService = Depends(get_auth_service),
                  container: Container = Depends(get_container)):
    """Start a phone sign-up: creates an unverified account and texts it a 6-digit code."""
    phone = _phone(body.phone)
    _limit(container.auth_limits.signup_per_ip, _client_ip(request))
    _limit(container.auth_limits.login_per_email, f"phone:{phone}")
    try:
        auth.start_phone_signup(phone=phone, full_name=body.full_name, password=body.password,
                                sender=container.sms_sender)
    except SignupNotAllowedError:
        raise HTTPException(status.HTTP_403_FORBIDDEN, SIGNUP_NOT_ALLOWED_PHONE)
    except PhoneTakenError:
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this phone number already exists.")
    return MessageResponse(detail=f"We sent a 6-digit code to {mask_phone(phone)}.")


@router.post("/phone/resend", response_model=MessageResponse, status_code=status.HTTP_202_ACCEPTED)
def phone_resend(body: PhoneCodeRequest, request: Request, auth: AuthService = Depends(get_auth_service),
                 container: Container = Depends(get_container)):
    phone = _phone(body.phone)
    _limit(container.auth_limits.login_per_email, f"phone:{phone}")
    auth.resend_phone_code(phone=phone, sender=container.sms_sender)
    # Same answer whether or not the number is waiting to be verified.
    return MessageResponse(detail=f"If {mask_phone(phone)} is waiting to be verified, a new code is on its way.")


@router.post("/phone/verify", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
def phone_verify(body: PhoneVerifyRequest, request: Request, auth: AuthService = Depends(get_auth_service),
                 container: Container = Depends(get_container)):
    phone = _phone(body.phone)
    _limit(container.auth_limits.login_per_ip, _client_ip(request))
    try:
        user, token, expires_in = auth.verify_phone(phone=phone, code=body.code)
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user), created=True)


@router.post("/phone/login", response_model=TokenResponse)
def phone_login(body: PhoneLoginRequest, request: Request, auth: AuthService = Depends(get_auth_service),
                container: Container = Depends(get_container)):
    phone = _phone(body.phone)
    _limit(container.auth_limits.login_per_ip, _client_ip(request))
    _limit(container.auth_limits.login_per_email, f"phone:{phone}")
    try:
        user, token, expires_in = auth.login_with_phone(phone=phone, password=body.password)
    except PhoneNotVerifiedError:
        raise HTTPException(status.HTTP_403_FORBIDDEN,
                            "This number hasn't been verified yet. Enter the code we sent you.")
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user))


@router.post("/phone/password-reset/request", response_model=MessageResponse,
             status_code=status.HTTP_202_ACCEPTED)
def request_phone_password_reset(body: PhoneCodeRequest, request: Request,
                                 auth: AuthService = Depends(get_auth_service),
                                 container: Container = Depends(get_container)):
    """Text a 6-digit reset code. The answer is the same whether or not the account exists."""
    phone = _phone(body.phone)
    _limit(container.auth_limits.reset_request_per_ip, _client_ip(request))
    _limit(container.auth_limits.reset_request_per_email, f"phone:{phone}")
    auth.request_phone_password_reset(phone, container.sms_sender)
    return MessageResponse(detail=f"If {mask_phone(phone)} has an account, we've sent it a 6-digit code.")


@router.post("/phone/password-reset/confirm", response_model=TokenResponse)
def confirm_phone_password_reset(body: PhonePasswordResetConfirm, request: Request,
                                 auth: AuthService = Depends(get_auth_service),
                                 container: Container = Depends(get_container)):
    """Set a new password with the texted code, and sign in."""
    phone = _phone(body.phone)
    _limit(container.auth_limits.reset_confirm_per_ip, _client_ip(request))
    try:
        user, token, expires_in = auth.confirm_phone_password_reset(
            phone=phone, code=body.code, new_password=body.new_password)
    except AuthError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user))


@router.post("/google", response_model=TokenResponse)
def google_sign_in(body: GoogleSignInRequest, request: Request, auth: AuthService = Depends(get_auth_service),
                   container: Container = Depends(get_container)):
    """Sign in, or sign up, with the ID token from the app's "Continue with Google" button."""
    if container.google is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Google sign-in isn't set up on this server.")
    _limit(container.auth_limits.google_per_ip, _client_ip(request))
    try:
        identity = container.google.verify(body.id_token)
        user, token, expires_in, created = auth.google_sign_in(identity)
    except InvalidGoogleToken as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc))
    except SignupNotAllowedError:
        raise HTTPException(status.HTTP_403_FORBIDDEN, SIGNUP_NOT_ALLOWED)
    except AuthError as exc:
        raise HTTPException(status.HTTP_409_CONFLICT, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user), created=created)


@router.post("/password-reset/request", response_model=MessageResponse, status_code=status.HTTP_202_ACCEPTED)
def request_password_reset(body: PasswordResetRequest, request: Request,
                           auth: AuthService = Depends(get_auth_service),
                           container: Container = Depends(get_container)):
    """Email a 6-digit reset code. The answer is the same whether or not the account exists."""
    if container.email_sender is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                            "Password reset by email isn't set up on this server. Ask the InhibitAI team for help.")
    _limit(container.auth_limits.reset_request_per_ip, _client_ip(request))
    _limit(container.auth_limits.reset_request_per_email, body.email.lower())
    try:
        auth.request_password_reset(body.email, container.email_sender)
    except OSError as exc:  # SMTP server unreachable or refused us
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Couldn't send the email. Please try again later.") from exc
    return MessageResponse(detail="If an account exists for that email, we've sent it a 6-digit code.")


@router.post("/password-reset/confirm", response_model=TokenResponse)
def confirm_password_reset(body: PasswordResetConfirm, request: Request,
                           auth: AuthService = Depends(get_auth_service),
                           container: Container = Depends(get_container)):
    """Set a new password with the emailed code, and sign in."""
    _limit(container.auth_limits.reset_confirm_per_ip, _client_ip(request))
    try:
        user, token, expires_in = auth.confirm_password_reset(email=body.email, code=body.code,
                                                              new_password=body.new_password)
    except AuthError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))
    return TokenResponse(access_token=token, expires_in=expires_in, user=UserOut.of(user))


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(get_current_user)):
    return UserOut.of(user)


@router.patch("/me", response_model=UserOut)
def update_me(body: ProfileUpdate, user: User = Depends(get_current_user),
              auth: AuthService = Depends(get_auth_service)):
    # exclude_unset: a field the app didn't send is left as it is, rather than cleared.
    return UserOut.of(auth.update_profile(user, **body.model_dump(exclude_unset=True)))


@router.post("/change-password", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
def change_password(body: PasswordChange, user: User = Depends(get_current_user),
                    auth: AuthService = Depends(get_auth_service)):
    try:
        auth.change_password(user, current_password=body.current_password, new_password=body.new_password)
    except AuthError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))
    return Response(status_code=status.HTTP_204_NO_CONTENT)
