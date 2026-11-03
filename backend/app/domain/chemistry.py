"""Chemistry Service (domain layer): parse, validate, standardise and draw SMILES.

Deliberately has no knowledge of the model, so validating or drawing a compound never
loads the classifier into memory.
"""

from dataclasses import dataclass
from functools import lru_cache
from typing import Literal

from rdkit import Chem, DataStructs, RDLogger
from rdkit.Chem import Descriptors, rdDepictor, rdFingerprintGenerator, rdMolDescriptors
from rdkit.Chem.Draw import rdMolDraw2D
from rdkit.Chem.MolStandardize import rdMolStandardize

RDLogger.DisableLog("rdApp.*")  # we turn RDKit problems into our own error messages
rdDepictor.SetPreferCoordGen(True)

# Fingerprint used for "have I tested something like this before?" searches. It is
# independent from the model's features, which are configured by the model bundle.
SIMILARITY_RADIUS = 2
SIMILARITY_BITS = 2048

Theme = Literal["light", "dark"]
ImageFormat = Literal["png", "svg"]


class InvalidSmilesError(ValueError):
    pass


@dataclass(frozen=True)
class PreparedMolecule:
    input_smiles: str
    canonical_smiles: str  # canonical SMILES of the standardised parent: what the model sees
    inchikey: str | None
    formula: str
    molecular_weight: float
    heavy_atoms: int
    mol: Chem.Mol


class ChemistryService:
    def __init__(self, *, tautomer_canonicalization: bool = True, max_tautomers: int = 200,
                 max_smiles_length: int = 1000, max_heavy_atoms: int = 200):
        self.tautomer_canonicalization = tautomer_canonicalization
        self.max_tautomers = max_tautomers
        self.max_smiles_length = max_smiles_length
        self.max_heavy_atoms = max_heavy_atoms
        # lru_cache on a bound method would keep `self` alive; wrap per instance instead.
        self._depict_cached = lru_cache(maxsize=2048)(self._depict)

    # ---------- validation ----------

    def parse(self, smiles: str) -> Chem.Mol:
        """Parse and sanitise a SMILES string, raising InvalidSmilesError with a readable reason."""
        smiles = (smiles or "").strip()
        if not smiles:
            raise InvalidSmilesError("Please enter a SMILES string.")
        if len(smiles) > self.max_smiles_length:
            raise InvalidSmilesError(f"SMILES is too long (maximum {self.max_smiles_length} characters).")
        if any(ch.isspace() for ch in smiles):
            raise InvalidSmilesError("SMILES must not contain spaces.")

        mol = Chem.MolFromSmiles(smiles, sanitize=False)
        if mol is None:
            raise InvalidSmilesError(
                "Invalid SMILES: the string could not be parsed. Check brackets, ring-closure digits and atom symbols."
            )
        problems = Chem.DetectChemistryProblems(mol)
        if problems:
            raise InvalidSmilesError(f"Invalid structure: {problems[0].Message()}")
        try:
            Chem.SanitizeMol(mol)
        except Exception as exc:  # RDKit raises several sanitisation exception types
            raise InvalidSmilesError(f"Invalid structure: {exc}") from exc
        if mol.GetNumAtoms() == 0:
            raise InvalidSmilesError("The SMILES string contains no atoms.")
        if mol.GetNumHeavyAtoms() > self.max_heavy_atoms:
            raise InvalidSmilesError(f"Molecule is too large (more than {self.max_heavy_atoms} heavy atoms).")
        # Re-parse normally so the molecule is exactly what Chem.MolFromSmiles gives the notebook
        # (a sanitised parse also assigns stereochemistry, which the step-by-step checks above skip).
        parsed = Chem.MolFromSmiles(smiles)
        if parsed is None:
            raise InvalidSmilesError("Invalid SMILES: RDKit could not read this structure.")
        return parsed

    def standardise(self, mol: Chem.Mol) -> Chem.Mol:
        """The cleaning the training data went through, identical to `prepare()` in the team's
        PARP1_model_colab notebook and model/predict.py: keep the largest fragment (strips salts and
        solvents), normalise functional groups, neutralise charges, canonicalise the tautomer
        (at most 200 enumerated). If any step fails, the molecule is used as parsed, as there."""
        try:
            m = rdMolStandardize.LargestFragmentChooser().choose(mol)
            m = rdMolStandardize.Normalizer().normalize(m)
            m = rdMolStandardize.Uncharger().uncharge(m)
            if self.tautomer_canonicalization:
                enumerator = rdMolStandardize.TautomerEnumerator()
                enumerator.SetMaxTautomers(self.max_tautomers)
                m = enumerator.Canonicalize(m)
            return m
        except Exception:
            return mol

    def prepare(self, smiles: str) -> PreparedMolecule:
        mol = self.standardise(self.parse(smiles))
        try:
            inchikey = Chem.MolToInchiKey(mol) or None
        except Exception:
            inchikey = None
        return PreparedMolecule(
            input_smiles=smiles.strip(),
            canonical_smiles=Chem.MolToSmiles(mol),
            inchikey=inchikey,
            formula=rdMolDescriptors.CalcMolFormula(mol),
            molecular_weight=round(Descriptors.MolWt(mol), 3),
            heavy_atoms=mol.GetNumHeavyAtoms(),
            mol=mol,
        )

    # ---------- similarity ----------

    @staticmethod
    def similarity_fingerprint(mol: Chem.Mol) -> bytes:
        """Raw bit string: the same bytes the RDKit cartridge's bfp type uses."""
        generator = rdFingerprintGenerator.GetMorganGenerator(radius=SIMILARITY_RADIUS, fpSize=SIMILARITY_BITS)
        return DataStructs.BitVectToBinaryText(generator.GetFingerprint(mol))

    @staticmethod
    def tanimoto_many(query: bytes, candidates: list[bytes]) -> list[float]:
        if not candidates:
            return []
        q = DataStructs.CreateFromBinaryText(query)
        return list(DataStructs.BulkTanimotoSimilarity(q, [DataStructs.CreateFromBinaryText(c) for c in candidates]))

    # ---------- 2D depiction ----------

    def depict(self, smiles: str, *, width: int = 300, height: int = 300, theme: Theme = "light",
               fmt: ImageFormat = "png") -> bytes:
        """Draw the structure exactly as entered (no standardisation) on a transparent background."""
        mol = self.parse(smiles)  # validate before touching the cache so errors aren't cached
        return self._depict_cached(Chem.MolToSmiles(mol), width, height, theme, fmt)

    def _depict(self, smiles: str, width: int, height: int, theme: Theme, fmt: ImageFormat) -> bytes:
        mol = Chem.MolFromSmiles(smiles)
        drawer = rdMolDraw2D.MolDraw2DCairo(width, height) if fmt == "png" else rdMolDraw2D.MolDraw2DSVG(width, height)
        options = drawer.drawOptions()
        if theme == "dark":
            rdMolDraw2D.SetDarkMode(options)
        options.setBackgroundColour((0, 0, 0, 0))
        options.bondLineWidth = max(1, round(min(width, height) / 150))
        options.padding = 0.08
        rdMolDraw2D.PrepareAndDrawMolecule(drawer, mol)
        drawer.FinishDrawing()
        data = drawer.GetDrawingText()
        return data if isinstance(data, bytes) else data.encode()
