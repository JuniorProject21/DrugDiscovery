from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.api.deps import Container, get_auth_service, get_container, get_current_user
from app.api.schemas import (
    GoogleSignInRequest,
    LoginRequest,
    MessageResponse,
    PasswordChange,
    PasswordResetConfirm,
    PasswordResetRequest,
    ProfileUpdate,
    RegisterRequest,
    TokenResponse,
    UserOut,
)
from app.data.models import User
from app.services.auth import AuthError, AuthService, EmailTakenError, SignupNotAllowedError
from app.services.google import InvalidGoogleToken
from app.services.ratelimit import RateLimiter

SIGNUP_NOT_ALLOWED = "Sign-up is limited to invited email addresses. Ask the InhibitAI team to add yours."

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
    return UserOut.of(auth.update_profile(user, full_name=body.full_name))


@router.post("/change-password", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
def change_password(body: PasswordChange, user: User = Depends(get_current_user),
                    auth: AuthService = Depends(get_auth_service)):
    try:
        auth.change_password(user, current_password=body.current_password, new_password=body.new_password)
    except AuthError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc))
    return Response(status_code=status.HTTP_204_NO_CONTENT)
