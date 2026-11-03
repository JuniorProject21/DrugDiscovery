"""Prediction workflow (application layer): validate -> name -> check cache -> infer -> save.

This is the coordination step from the architecture diagram in the progress report.
"""

from collections.abc import Sequence
from dataclasses import asdict

from sqlalchemy.orm import Session

from app.data.models import Submission, User
from app.data.repositories import MoleculeRepository, PredictionRepository, SubmissionRepository, UserRepository
from app.domain.chemistry import ChemistryService, PreparedMolecule
from app.domain.inference.service import InferenceService
from app.domain.naming import CompoundNames, PubChemNamer


class SubmissionNotFound(Exception):
    pass


class PredictionService:
    def __init__(self, session: Session, chemistry: ChemistryService, inference: InferenceService, *,
                 namer: PubChemNamer | None = None, rdkit_cartridge: bool = False, compound_prefix: str = "XYZ"):
        self.session = session
        self.chemistry = chemistry
        self.inference = inference
        self.namer = namer
        self.compound_prefix = compound_prefix
        self.users = UserRepository(session)
        self.molecules = MoleculeRepository(session)
        self.predictions = PredictionRepository(session)
        self.submissions = SubmissionRepository(session, rdkit_cartridge=rdkit_cartridge)

    def predict(self, user: User, smiles: str) -> tuple[Submission, bool]:
        """Returns the new history entry and whether the prediction came from the cache.

        Raises InvalidSmilesError (bad input) or ModelUnavailableError (no model loaded).
        """
        prepared = self.chemistry.prepare(smiles)
        model = self.inference.load()
        names = self._names_to_record(prepared)

        molecule = self.molecules.get_or_create(
            canonical_smiles=prepared.canonical_smiles,
            inchikey=prepared.inchikey,
            fingerprint=self.chemistry.similarity_fingerprint(prepared.mol),
            formula=prepared.formula,
            molecular_weight=prepared.molecular_weight,
        )
        if names is not None and molecule.names_checked_at is None:
            self.molecules.record_names(molecule, common_name=names.common_name, iupac_name=names.iupac_name,
                                        pubchem_cid=names.pubchem_cid)
        prediction = self.predictions.get_cached(molecule.id, model.model_version)
        cached = prediction is not None
        if not cached:
            result = model.predict(prepared.mol)
            prediction = self.predictions.get_or_create(
                molecule_id=molecule.id, model_version=model.model_version, values=asdict(result),
            )

        # Known compounds are listed under their name (e.g. "Olaparib"); others get the next XYZ-### label.
        name = molecule.common_name or f"{self.compound_prefix}-{self.users.take_compound_number(user.id):03d}"
        submission = self.submissions.create(
            user_id=user.id, molecule=molecule, prediction=prediction, name=name,
            input_smiles=prepared.input_smiles,
        )
        self.session.commit()
        return submission, cached

    def _names_to_record(self, prepared: PreparedMolecule) -> CompoundNames | None:
        """PubChem names for a structure we haven't named yet (None if known already or unavailable)."""
        if self.namer is None:
            return None
        existing = self.molecules.get_by_smiles(prepared.canonical_smiles)
        if existing is not None and existing.names_checked_at is not None:
            return None
        self.session.rollback()  # don't hold the database open while waiting for PubChem
        return self.namer.lookup(prepared.inchikey)

    def history(self, user: User, *, limit: int, offset: int) -> tuple[Sequence[Submission], int]:
        return (self.submissions.list_for_user(user.id, limit=limit, offset=offset),
                self.submissions.count_for_user(user.id))

    def get(self, user: User, submission_id: int) -> Submission:
        submission = self.submissions.get_for_user(user.id, submission_id)
        if submission is None:
            raise SubmissionNotFound
        return submission

    def update(self, user: User, submission_id: int, *, is_saved: bool | None = None,
               name: str | None = None) -> Submission:
        submission = self.get(user, submission_id)
        if is_saved is not None:
            submission.is_saved = is_saved
        if name is not None:
            submission.name = name
        self.session.commit()
        return submission

    def delete(self, user: User, submission_id: int) -> None:
        self.submissions.delete(self.get(user, submission_id))
        self.session.commit()

    def find_similar(self, user: User, smiles: str, *, min_similarity: float,
                     limit: int) -> list[tuple[Submission, float]]:
        """Has this user already tested this compound, or something close to it?"""
        prepared = self.chemistry.prepare(smiles)
        return self.submissions.find_similar(
            user.id, self.chemistry.similarity_fingerprint(prepared.mol),
            min_similarity=min_similarity, limit=limit, python_similarity=self.chemistry.tanimoto_many,
        )
