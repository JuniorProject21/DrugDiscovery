"""Why did the model give this score? (report section 5.2, "Interpretability").

The model sees a molecule only as 2,048 fingerprint bits, and every bit that is set stands for a
concrete piece of chemistry: an atom and everything within a few bonds of it. So the question
"which parts of this molecule pushed the score up?" can be answered by taking the bits away one at
a time and watching the score move. Removing a bit asks the trained model a question it can
answer exactly, which is why this is done by occlusion rather than by reading importances off the
forest: a tree's global importance says which bits matter across the whole training set, not which
ones mattered for the compound in front of the user.

The cost is one extra prediction per set bit (a typical drug-like molecule sets 30-80 of them), and
they are all evaluated in a single batched call.
"""

from dataclasses import dataclass

import numpy as np
from rdkit import Chem


@dataclass(frozen=True)
class FragmentContribution:
    """One fingerprint bit, and what the molecule's score loses when it is taken away."""

    bit: int
    weight: float  # score with the bit - score without it. Positive = pushed towards "active".
    radius: int  # how many bonds out from the central atom this piece of chemistry reaches
    atoms: tuple[int, ...]  # atom indices, matching a fresh parse of the canonical SMILES
    fragment: str  # the piece of chemistry itself, as a SMILES string
    occurrences: int  # how many places in the molecule set this same bit


@dataclass(frozen=True)
class Explanation:
    canonical_smiles: str  # the atom indices in `fragments` refer to this exact string
    score: float
    threshold: float
    label: str
    fragments: tuple[FragmentContribution, ...]  # largest absolute weight first


def _fragment_smiles(mol: Chem.Mol, atom: int, radius: int) -> tuple[tuple[int, ...], str]:
    """The atoms within `radius` bonds of `atom`, and how that piece of chemistry reads as SMILES."""
    if radius == 0:
        return (atom,), mol.GetAtomWithIdx(atom).GetSymbol()
    bonds = Chem.FindAtomEnvironmentOfRadiusN(mol, radius, atom)
    if not bonds:
        return (atom,), mol.GetAtomWithIdx(atom).GetSymbol()
    atoms = {atom}
    for b in bonds:
        bond = mol.GetBondWithIdx(b)
        atoms.add(bond.GetBeginAtomIdx())
        atoms.add(bond.GetEndAtomIdx())
    ordered = tuple(sorted(atoms))
    try:
        smiles = Chem.MolFragmentToSmiles(mol, atomsToUse=list(ordered), bondsToUse=list(bonds),
                                          rootedAtAtom=atom, canonical=True)
    except Exception:
        smiles = ""
    return ordered, smiles


def explain_prediction(
    *,
    canonical_smiles: str,
    featurizer,
    classifiers,
    positive_index,
    threshold: float,
    label: str,
    score: float,
    bit_info: dict[int, tuple[tuple[int, int], ...]],
    max_fragments: int = 8,
) -> Explanation:
    """Score each set bit by how much the prediction drops without it.

    `bit_info` maps a set bit to the (central atom, radius) environments that switched it on, and
    must have been produced from a fresh parse of `canonical_smiles` so the atom indices line up
    with the drawing the app will ask for.
    """
    mol = Chem.MolFromSmiles(canonical_smiles)
    if mol is None:
        raise ValueError(f"Could not parse the canonical SMILES {canonical_smiles!r}")

    bits = sorted(bit_info)
    if not bits:
        return Explanation(canonical_smiles, score, threshold, label, ())

    base = featurizer.transform([mol]).astype(np.float64)
    # One row per bit, that row being the molecule with just that bit switched off.
    variants = np.repeat(base, len(bits), axis=0)
    for row, bit in enumerate(bits):
        variants[row, bit] = 0
    without = np.mean(
        [est.predict_proba(variants)[:, idx] for est, idx in zip(classifiers, positive_index)],
        axis=0,
    )

    contributions = []
    for row, bit in enumerate(bits):
        environments = bit_info[bit]
        atom, radius = environments[0]
        atoms, fragment = _fragment_smiles(mol, atom, radius)
        # Highlight every place this bit came from, not just the first.
        all_atoms = set(atoms)
        for other_atom, other_radius in environments[1:]:
            all_atoms.update(_fragment_smiles(mol, other_atom, other_radius)[0])
        contributions.append(FragmentContribution(
            bit=bit,
            weight=float(score - without[row]),
            radius=radius,
            atoms=tuple(sorted(all_atoms)),
            fragment=fragment,
            occurrences=len(environments),
        ))

    contributions.sort(key=lambda c: abs(c.weight), reverse=True)
    meaningful = [c for c in contributions if abs(c.weight) > 1e-9][:max_fragments]
    return Explanation(canonical_smiles, score, threshold, label, tuple(meaningful))
