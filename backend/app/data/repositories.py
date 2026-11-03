"""Data access layer: turns application calls into SQL using the repository pattern.

Nothing above this module writes SQL, so the database can be swapped (SQLite in tests,
PostgreSQL with or without the RDKit cartridge in production) without touching the services.
"""

from collections.abc import Callable, Sequence
from datetime import datetime, timezone

from sqlalchemy import delete, func, select, text, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.data.models import Molecule, PasswordResetCode, Prediction, Submission, User

# (query fingerprint, candidate fingerprints) -> Tanimoto similarities
SimilarityFn = Callable[[bytes, list[bytes]], list[float]]


class UserRepository:
    def __init__(self, session: Session):
        self.session = session

    def get(self, user_id: int) -> User | None:
        return self.session.get(User, user_id)

    def get_by_email(self, email: str) -> User | None:
        return self.session.scalar(select(User).where(User.email == email.lower()))

    def get_by_google_sub(self, subject: str) -> User | None:
        return self.session.scalar(select(User).where(User.google_sub == subject))

    def create(self, *, email: str, password_hash: str, full_name: str) -> User:
        user = User(email=email.lower(), password_hash=password_hash, full_name=full_name)
        self.session.add(user)
        self.session.flush()
        return user

    def take_compound_number(self, user_id: int) -> int:
        """Atomically reserve the user's next display number (1, 2, 3, ...)."""
        stmt = (
            update(User)
            .where(User.id == user_id)
            .values(next_compound_number=User.next_compound_number + 1)
            .returning(User.next_compound_number)
        )
        return self.session.execute(stmt).scalar_one() - 1


class PasswordResetRepository:
    def __init__(self, session: Session):
        self.session = session

    def replace_for_user(self, user_id: int, *, code_hash: str, expires_at: datetime) -> PasswordResetCode:
        """Store a new code; any earlier code for this user stops working."""
        self.session.execute(delete(PasswordResetCode).where(PasswordResetCode.user_id == user_id))
        code = PasswordResetCode(user_id=user_id, code_hash=code_hash, expires_at=expires_at)
        self.session.add(code)
        self.session.flush()
        return code

    def active_for_user(self, user_id: int) -> PasswordResetCode | None:
        return self.session.scalar(
            select(PasswordResetCode)
            .where(PasswordResetCode.user_id == user_id, PasswordResetCode.used_at.is_(None),
                   PasswordResetCode.expires_at > datetime.now(timezone.utc))
            .order_by(PasswordResetCode.created_at.desc())
        )


class MoleculeRepository:
    def __init__(self, session: Session):
        self.session = session

    def get_by_smiles(self, canonical_smiles: str) -> Molecule | None:
        return self.session.scalar(select(Molecule).where(Molecule.canonical_smiles == canonical_smiles))

    def get_or_create(self, *, canonical_smiles: str, inchikey: str | None, fingerprint: bytes,
                      formula: str | None = None, molecular_weight: float | None = None) -> Molecule:
        existing = self.get_by_smiles(canonical_smiles)
        if existing is not None:
            if existing.formula is None and formula is not None:  # rows from before these columns existed
                existing.formula, existing.molecular_weight = formula, molecular_weight
            return existing
        molecule = Molecule(canonical_smiles=canonical_smiles, inchikey=inchikey, fingerprint=fingerprint,
                            formula=formula, molecular_weight=molecular_weight)
        try:
            with self.session.begin_nested():
                self.session.add(molecule)
        except IntegrityError:
            # Another request inserted the same structure between our SELECT and INSERT.
            return self.get_by_smiles(canonical_smiles)
        return molecule

    def record_names(self, molecule: Molecule, *, common_name: str | None, iupac_name: str | None,
                     pubchem_cid: int | None) -> None:
        molecule.common_name, molecule.iupac_name, molecule.pubchem_cid = common_name, iupac_name, pubchem_cid
        molecule.names_checked_at = datetime.now(timezone.utc)


class PredictionRepository:
    def __init__(self, session: Session):
        self.session = session

    def get_cached(self, molecule_id: int, model_version: str) -> Prediction | None:
        return self.session.scalar(
            select(Prediction).where(Prediction.molecule_id == molecule_id, Prediction.model_version == model_version)
        )

    def get_or_create(self, *, molecule_id: int, model_version: str, values: dict) -> Prediction:
        prediction = Prediction(molecule_id=molecule_id, model_version=model_version, **values)
        try:
            with self.session.begin_nested():
                self.session.add(prediction)
        except IntegrityError:
            return self.get_cached(molecule_id, model_version)
        return prediction


class SubmissionRepository:
    def __init__(self, session: Session, rdkit_cartridge: bool = False):
        self.session = session
        self.rdkit_cartridge = rdkit_cartridge

    def create(self, *, user_id: int, molecule: Molecule, prediction: Prediction,
               name: str, input_smiles: str) -> Submission:
        submission = Submission(
            user_id=user_id, molecule=molecule, prediction=prediction, name=name, input_smiles=input_smiles,
        )
        self.session.add(submission)
        self.session.flush()
        return submission

    def list_for_user(self, user_id: int, *, limit: int, offset: int) -> Sequence[Submission]:
        stmt = (
            select(Submission)
            .where(Submission.user_id == user_id)
            .order_by(Submission.created_at.desc(), Submission.id.desc())
            .limit(limit)
            .offset(offset)
        )
        return self.session.scalars(stmt).all()

    def count_for_user(self, user_id: int) -> int:
        return self.session.scalar(select(func.count()).select_from(Submission).where(Submission.user_id == user_id))

    def get_for_user(self, user_id: int, submission_id: int) -> Submission | None:
        return self.session.scalar(
            select(Submission).where(Submission.id == submission_id, Submission.user_id == user_id)
        )

    def delete(self, submission: Submission) -> None:
        self.session.delete(submission)
        self.session.flush()

    def find_similar(self, user_id: int, fingerprint: bytes, *, min_similarity: float, limit: int,
                     python_similarity: SimilarityFn) -> list[tuple[Submission, float]]:
        """The user's past submissions whose structure is similar to `fingerprint`, most similar first."""
        if self.rdkit_cartridge:
            scored = self._similar_ids_cartridge(user_id, fingerprint, min_similarity, limit)
        else:
            scored = self._similar_ids_python(user_id, fingerprint, min_similarity, limit, python_similarity)
        if not scored:
            return []
        by_id = {s.id: s for s in self.session.scalars(select(Submission).where(Submission.id.in_([i for i, _ in scored])))}
        return [(by_id[i], sim) for i, sim in scored if i in by_id]

    def _similar_ids_cartridge(self, user_id, fingerprint, min_similarity, limit) -> list[tuple[int, float]]:
        # `%` is the cartridge's indexed Tanimoto operator; it uses rdkit.tanimoto_threshold.
        self.session.execute(text("SELECT set_config('rdkit.tanimoto_threshold', :t, true)"),
                             {"t": str(min_similarity)})
        rows = self.session.execute(
            text(
                "SELECT s.id, tanimoto_sml(m.mfp2, bfp_from_binary_text(:fp)) AS similarity "
                "FROM submissions s JOIN molecules m ON m.id = s.molecule_id "
                "WHERE s.user_id = :user_id AND m.mfp2 % bfp_from_binary_text(:fp) "
                "ORDER BY similarity DESC, s.created_at DESC LIMIT :limit"
            ),
            {"fp": fingerprint, "user_id": user_id, "limit": limit},
        ).all()
        return [(row.id, float(row.similarity)) for row in rows]

    def _similar_ids_python(self, user_id, fingerprint, min_similarity, limit, python_similarity):
        rows = self.session.execute(
            select(Submission.id, Submission.created_at, Molecule.fingerprint)
            .join(Molecule, Molecule.id == Submission.molecule_id)
            .where(Submission.user_id == user_id)
        ).all()
        if not rows:
            return []
        sims = python_similarity(fingerprint, [row.fingerprint for row in rows])
        hits = [(row, sim) for row, sim in zip(rows, sims) if sim >= min_similarity]
        hits.sort(key=lambda h: (h[1], h[0].created_at), reverse=True)
        return [(row.id, float(sim)) for row, sim in hits[:limit]]
