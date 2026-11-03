from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.api.deps import get_container, get_current_user, get_prediction_service
from app.api.schemas import (
    PredictResponse,
    SimilarHit,
    SimilarRequest,
    SmilesRequest,
    SubmissionOut,
    SubmissionPage,
    SubmissionPatch,
)
from app.data.models import User
from app.domain.chemistry import InvalidSmilesError
from app.domain.inference.service import ModelUnavailableError
from app.services.predictions import PredictionService, SubmissionNotFound

router = APIRouter(prefix="/predictions", tags=["predictions"])


def _target(container=Depends(get_container)) -> str:
    return container.settings.target_name


@router.post("", response_model=PredictResponse, status_code=status.HTTP_201_CREATED)
def create_prediction(body: SmilesRequest, user: User = Depends(get_current_user),
                      service: PredictionService = Depends(get_prediction_service), target: str = Depends(_target)):
    try:
        submission, cached = service.predict(user, body.smiles)
    except InvalidSmilesError as exc:
        raise HTTPException(422, str(exc))
    except ModelUnavailableError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc))
    return PredictResponse.of(submission, target, cached=cached)


@router.get("", response_model=SubmissionPage)
def list_predictions(limit: int = Query(500, ge=1, le=1000), offset: int = Query(0, ge=0),
                     user: User = Depends(get_current_user),
                     service: PredictionService = Depends(get_prediction_service), target: str = Depends(_target)):
    items, total = service.history(user, limit=limit, offset=offset)
    return SubmissionPage(items=[SubmissionOut.of(s, target) for s in items], total=total, limit=limit, offset=offset)


@router.post("/similar", response_model=list[SimilarHit])
def similar_predictions(body: SimilarRequest, user: User = Depends(get_current_user),
                        service: PredictionService = Depends(get_prediction_service), target: str = Depends(_target)):
    """The signed-in user's earlier submissions that are structurally similar to this SMILES."""
    try:
        hits = service.find_similar(user, body.smiles, min_similarity=body.min_similarity, limit=body.limit)
    except InvalidSmilesError as exc:
        raise HTTPException(422, str(exc))
    return [SimilarHit(similarity=sim, submission=SubmissionOut.of(s, target)) for s, sim in hits]


@router.get("/{submission_id}", response_model=SubmissionOut)
def get_prediction(submission_id: int, user: User = Depends(get_current_user),
                   service: PredictionService = Depends(get_prediction_service), target: str = Depends(_target)):
    try:
        return SubmissionOut.of(service.get(user, submission_id), target)
    except SubmissionNotFound:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Prediction not found.")


@router.patch("/{submission_id}", response_model=SubmissionOut)
def update_prediction(submission_id: int, body: SubmissionPatch, user: User = Depends(get_current_user),
                      service: PredictionService = Depends(get_prediction_service), target: str = Depends(_target)):
    try:
        return SubmissionOut.of(service.update(user, submission_id, is_saved=body.is_saved, name=body.name), target)
    except SubmissionNotFound:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Prediction not found.")


@router.delete("/{submission_id}", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
def delete_prediction(submission_id: int, user: User = Depends(get_current_user),
                      service: PredictionService = Depends(get_prediction_service)):
    try:
        service.delete(user, submission_id)
    except SubmissionNotFound:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Prediction not found.")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
