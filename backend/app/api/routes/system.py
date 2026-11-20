from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import text

from app.api.deps import Container, get_container, get_current_user
from app.api.schemas import HealthResponse, ModelInfo, ModelStatus
from app.domain.inference.service import ModelUnavailableError

router = APIRouter(tags=["system"])


@router.get("/health", response_model=HealthResponse)
def health(container: Container = Depends(get_container)):
    """Liveness check; never loads the model."""
    try:
        with container.session_factory() as session:
            session.execute(text("SELECT 1"))
        db_ok = True
    except Exception:
        db_ok = False
    model = container.inference.status()
    return HealthResponse(
        status="ok" if db_ok and model["error"] is None else "degraded",
        database=container.db_features.dialect if db_ok else "unreachable",
        rdkit_cartridge=container.db_features.rdkit_cartridge,
        model=ModelStatus(**model),
    )


@router.get("/model", response_model=ModelInfo, dependencies=[Depends(get_current_user)])
def model_info(container: Container = Depends(get_container)):
    try:
        return ModelInfo(**container.inference.load().info())
    except ModelUnavailableError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc))
