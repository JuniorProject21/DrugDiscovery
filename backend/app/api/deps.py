from collections.abc import Iterator
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.data.db import DatabaseFeatures
from app.data.models import User
from app.domain.chemistry import ChemistryService
from app.domain.inference.service import InferenceService
from app.domain.naming import PubChemNamer
from app.services.auth import AuthError, AuthService, TokenService
from app.services.email import EmailSender
from app.services.google import GoogleTokenVerifier
from app.services.predictions import PredictionService
from app.services.ratelimit import AuthRateLimits


@dataclass
class Container:
    """Long-lived objects shared by all requests, built once in create_app()."""

    settings: Settings
    session_factory: sessionmaker[Session]
    db_features: DatabaseFeatures
    chemistry: ChemistryService
    inference: InferenceService
    tokens: TokenService
    auth_limits: AuthRateLimits
    namer: PubChemNamer
    email_sender: EmailSender | None  # None: password reset by email is switched off
    google: GoogleTokenVerifier | None  # None: Google sign-in isn't configured


def get_container(request: Request) -> Container:
    return request.app.state.container


def get_session(container: Container = Depends(get_container)) -> Iterator[Session]:
    with container.session_factory() as session:
        yield session


def get_chemistry(container: Container = Depends(get_container)) -> ChemistryService:
    return container.chemistry


def get_auth_service(session: Session = Depends(get_session),
                     container: Container = Depends(get_container)) -> AuthService:
    return AuthService(session, container.tokens, container.settings.signup_allowlist)


def get_prediction_service(session: Session = Depends(get_session),
                           container: Container = Depends(get_container)) -> PredictionService:
    return PredictionService(
        session, container.chemistry, container.inference,
        namer=container.namer,
        rdkit_cartridge=container.db_features.rdkit_cartridge,
        compound_prefix=container.settings.compound_prefix,
    )


_bearer = HTTPBearer(auto_error=False)


def get_current_user(credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
                     auth: AuthService = Depends(get_auth_service)) -> User:
    if credentials is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Please sign in.", headers={"WWW-Authenticate": "Bearer"})
    try:
        return auth.user_from_token(credentials.credentials)
    except AuthError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc), headers={"WWW-Authenticate": "Bearer"}) from exc
