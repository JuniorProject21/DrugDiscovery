"""Turn standardised molecules into the feature vectors the classifier was trained on."""

from dataclasses import asdict, dataclass

import numpy as np
from rdkit import Chem, DataStructs
from rdkit.Chem import MACCSkeys, rdFingerprintGenerator

SUPPORTED_KINDS = ("morgan", "morgan_count", "fcfp", "fcfp_count", "maccs")


@dataclass(frozen=True)
class FeaturizerConfig:
    kind: str = "morgan"
    radius: int = 2
    n_bits: int = 2048
    include_chirality: bool = False

    @classmethod
    def from_dict(cls, data: dict) -> "FeaturizerConfig":
        unknown = set(data) - set(cls.__dataclass_fields__)
        if unknown:
            raise ValueError(f"Unknown featurizer options: {sorted(unknown)}")
        config = cls(**data)
        if config.kind not in SUPPORTED_KINDS:
            raise ValueError(f"Unsupported featurizer kind {config.kind!r}; expected one of {SUPPORTED_KINDS}")
        return config

    def to_dict(self) -> dict:
        return asdict(self)


class Featurizer:
    def __init__(self, config: FeaturizerConfig):
        self.config = config

    def transform(self, mols: list[Chem.Mol]) -> np.ndarray:
        return np.vstack([self._featurize(mol) for mol in mols])

    def _featurize(self, mol: Chem.Mol) -> np.ndarray:
        c = self.config
        if c.kind == "maccs":
            arr = np.zeros((0,), dtype=np.uint8)
            DataStructs.ConvertToNumpyArray(MACCSkeys.GenMACCSKeys(mol), arr)
            return arr
        options = {"radius": c.radius, "fpSize": c.n_bits, "includeChirality": c.include_chirality}
        if c.kind.startswith("fcfp"):
            options["atomInvariantsGenerator"] = rdFingerprintGenerator.GetMorganFeatureAtomInvGen()
        generator = rdFingerprintGenerator.GetMorganGenerator(**options)
        if c.kind.endswith("_count"):
            return generator.GetCountFingerprintAsNumPy(mol)
        return generator.GetFingerprintAsNumPy(mol)
