"""InhibitAI backend.

Run with:  uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000

Layers (progress report, section 4.5):
  app/api       Application layer, HTTP side: routes, request/response schemas, auth checks
  app/services  Application layer, coordination: auth, validate -> cache -> infer -> save
  app/domain    Domain layer: Chemistry Service and Inference Service
  app/data      Data access layer (repositories) and the database tables
"""

import logging
import threading
from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.deps import Container
from app.api.routes import auth, chemistry, predictions, system
from app.config import Settings, get_settings
from app.data.db import init_db, make_engine, make_session_factory
from app.domain.chemistry import ChemistryService
from app.domain.inference.service import InferenceService
from app.domain.naming import PubChemNamer
from app.services.auth import TokenService
from app.services.email import ConsoleEmailSender, EmailSender, SmtpEmailSender
from app.services.google import GoogleTokenVerifier
from app.services.ratelimit import AuthRateLimits

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if settings.environment == "production":
        problems = settings.production_problems()
        if problems:
            raise RuntimeError("Refusing to start in production mode:\n  - " + "\n  - ".join(problems))
        if not settings.signup_allowlist:
            log.warning("INHIBITAI_ALLOWED_SIGNUP_EMAILS is empty: anyone who finds this server can create an account.")

    engine = make_engine(settings.database_url)
    chemistry_service = ChemistryService(
        tautomer_canonicalization=settings.tautomer_canonicalization,
        max_tautomers=settings.max_tautomers,
        max_smiles_length=settings.max_smiles_length,
        max_heavy_atoms=settings.max_heavy_atoms,
    )
    container = Container(
        settings=settings,
        session_factory=make_session_factory(engine),
        db_features=init_db(engine),
        chemistry=chemistry_service,
        inference=InferenceService(
            settings.model_bundle_path, chemistry_service,
            allow_stub=settings.allow_stub_model,
            skip_reference_check=settings.skip_bundle_reference_check,
            target=settings.target_name,
        ),
        tokens=TokenService(settings.jwt_secret, settings.jwt_expiry_minutes),
        auth_limits=AuthRateLimits(),
        namer=PubChemNamer(enabled=settings.pubchem_lookup),
        email_sender=_email_sender(settings),
        google=GoogleTokenVerifier(settings.google_client_id_list) if settings.google_client_id_list else None,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if settings.preload_model:
            # Load in the background so the server answers (and can draw molecules) straight away.
            threading.Thread(target=container.inference.try_load, name="model-loader", daemon=True).start()
        yield
        engine.dispose()

    app = FastAPI(title="InhibitAI API", version="1.0.0", lifespan=lifespan)
    app.state.container = container
    app.add_exception_handler(RequestValidationError, _validation_error_handler)

    api = APIRouter(prefix="/api/v1")
    for module in (system, auth, chemistry, predictions):
        api.include_router(module.router)
    app.include_router(api)
    return app


def _email_sender(settings: Settings) -> EmailSender | None:
    if settings.smtp_host:
        return SmtpEmailSender(host=settings.smtp_host, port=settings.smtp_port, username=settings.smtp_username,
                               password=settings.smtp_password, sender=settings.smtp_from or settings.smtp_username,
                               security=settings.smtp_security)
    if settings.environment == "production":
        log.warning("INHIBITAI_SMTP_HOST is not set: password reset by email is switched off.")
        return None
    return ConsoleEmailSender()


async def _validation_error_handler(_request: Request, exc: RequestValidationError) -> JSONResponse:
    """Flatten FastAPI's validation errors into one readable `detail` string the app can show."""
    messages = []
    for error in exc.errors():
        field = ".".join(str(part) for part in error["loc"] if part not in ("body", "query", "path"))
        message = error["msg"].removeprefix("Value error, ")
        messages.append(f"{field}: {message}" if field else message)
    return JSONResponse(
        status_code=422,
        content={"detail": "; ".join(messages), "errors": jsonable_encoder(exc.errors())},
    )
