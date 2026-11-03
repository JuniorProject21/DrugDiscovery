from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.api.deps import Container, get_chemistry, get_container, get_current_user
from app.api.schemas import DepictRequest, SmilesRequest, ValidateResponse
from app.domain.chemistry import ChemistryService, InvalidSmilesError

router = APIRouter(prefix="/chem", tags=["chemistry"], dependencies=[Depends(get_current_user)])


@router.post("/validate", response_model=ValidateResponse)
def validate(body: SmilesRequest, chemistry: ChemistryService = Depends(get_chemistry),
             container: Container = Depends(get_container)):
    """Check a SMILES string, show the standardised structure the model would see, and its name if
    PubChem knows the compound."""
    try:
        p = chemistry.prepare(body.smiles)
    except InvalidSmilesError as exc:
        return ValidateResponse(valid=False, error=str(exc))
    names = container.namer.lookup(p.inchikey)
    return ValidateResponse(
        valid=True, canonical_smiles=p.canonical_smiles, inchikey=p.inchikey, formula=p.formula,
        molecular_weight=p.molecular_weight, heavy_atoms=p.heavy_atoms,
        common_name=names.common_name if names else None, iupac_name=names.iupac_name if names else None,
        pubchem_cid=names.pubchem_cid if names else None,
    )


@router.post(
    "/depict",
    response_class=Response,
    responses={200: {"content": {"image/png": {}, "image/svg+xml": {}}, "description": "2D structure drawing"}},
)
def depict(body: DepictRequest, chemistry: ChemistryService = Depends(get_chemistry)):
    """2D drawing of the structure as entered, on a transparent background."""
    try:
        image = chemistry.depict(body.smiles, width=body.width, height=body.height, theme=body.theme, fmt=body.format)
    except InvalidSmilesError as exc:
        raise HTTPException(422, str(exc))
    media_type = "image/png" if body.format == "png" else "image/svg+xml"
    return Response(content=image, media_type=media_type, headers={"Cache-Control": "private, max-age=86400"})
