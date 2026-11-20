"""The assistant behind "Learn more" on a result.

The app sends only a compound and a question. Everything the model is told about that compound is
looked up here, from the same services that produced the result on screen, so the assistant can
explain the prediction but cannot invent a different one.
"""

from fastapi import APIRouter, Depends, HTTPException, status

from app.api.deps import get_container, get_current_user
from app.api.schemas import ChatRequest, ChatResponse
from app.data.models import User
from app.domain.chemistry import InvalidSmilesError
from app.domain.inference.service import ExplanationUnavailableError, ModelUnavailableError
from app.services.llm import SYSTEM_PROMPT, ChatTurn, LlmUnavailable, facts_prompt

router = APIRouter(prefix="/chat", tags=["chat"])

OPENING_QUESTION = "Explain this result to me in plain English."


@router.post("", response_model=ChatResponse)
def ask_about_result(body: ChatRequest, container=Depends(get_container),
                     user: User = Depends(get_current_user)):
    """Answer a question about one compound's prediction, grounded in this server's own numbers."""
    if not container.chat.enabled:
        raise HTTPException(
            status.HTTP_501_NOT_IMPLEMENTED,
            "The assistant isn't set up on this server. The \"why this result\" panel still "
            "explains which fragments moved the score.",
        )
    try:
        prepared = container.chemistry.prepare(body.smiles)
        model_version, result = container.inference.predict(prepared.mol)
    except InvalidSmilesError as exc:
        raise HTTPException(422, str(exc))
    except ModelUnavailableError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc))

    facts = {
        "canonical_smiles": prepared.canonical_smiles,
        "name": body.name,
        "formula": prepared.formula,
        "label": result.label,
        "score": result.score,
        "threshold": result.threshold,
        "confidence": result.confidence,
        "reliability": result.reliability,
        "nearest_similarity": result.nearest_similarity,
        "model_version": model_version,
        "adme": result.adme,
    }
    try:
        _, explanation = container.inference.explain(prepared.canonical_smiles)
        facts["fragments"] = [{"fragment": f.fragment, "weight": f.weight} for f in explanation.fragments]
    except (ExplanationUnavailableError, ModelUnavailableError, InvalidSmilesError):
        facts["fragments"] = []  # the placeholder model can't explain; the rest still grounds the answer

    turns = [ChatTurn(role=t.role, content=t.content) for t in body.history]
    turns.append(ChatTurn(role="user", content=(body.question or OPENING_QUESTION).strip()))
    try:
        reply = container.chat.reply(system=f"{SYSTEM_PROMPT}\n\n{facts_prompt(facts)}", turns=turns)
    except LlmUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc))
    return ChatResponse(reply=reply, model=container.chat.model)
