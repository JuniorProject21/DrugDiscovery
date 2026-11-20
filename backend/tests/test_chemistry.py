import pytest
from rdkit import Chem
from rdkit.Chem import rdFingerprintGenerator
from rdkit.Chem.MolStandardize import rdMolStandardize

from app.domain.chemistry import ChemistryService, InvalidSmilesError

chem = ChemistryService()


def test_salts_are_stripped_and_charges_neutralised():
    plain = chem.prepare("CC(=O)Oc1ccccc1C(=O)O")
    salt = chem.prepare("CC(=O)Oc1ccccc1C(=O)[O-].[Na+]")
    assert salt.canonical_smiles == plain.canonical_smiles


def test_tautomers_map_to_one_structure():
    # The phthalazinone group drawn in its two tautomeric forms (report, section 4.1).
    lactam = chem.prepare("O=c1[nH]ncc2ccccc12")
    lactim = chem.prepare("Oc1nncc2ccccc12")
    assert lactam.canonical_smiles == lactim.canonical_smiles
    assert lactam.inchikey == lactim.inchikey


def test_tautomer_step_can_be_turned_off():
    no_tautomers = ChemistryService(tautomer_canonicalization=False)
    assert no_tautomers.prepare("O=c1[nH]ncc2ccccc12").canonical_smiles != \
        no_tautomers.prepare("Oc1nncc2ccccc12").canonical_smiles


@pytest.mark.parametrize("smiles, message", [
    ("", "Please enter"),
    ("C1CC", "could not be parsed"),
    ("CN(C)(C)(C)C", "valence"),
    ("c1cccc1", "kekulize"),
    ("C C", "spaces"),
    ("C" * 1001, "too long"),
])
def test_invalid_smiles_get_readable_errors(smiles, message):
    with pytest.raises(InvalidSmilesError, match=message):
        chem.prepare(smiles)


def test_prepare_reports_properties():
    p = chem.prepare("NC(=O)c1ccccc1")
    assert p.formula == "C7H7NO"
    assert p.heavy_atoms == 9
    assert p.molecular_weight == pytest.approx(121.139, abs=0.01)


def test_depictions():
    png = chem.depict("NC(=O)c1ccccc1", width=120, height=100, theme="dark")
    assert png.startswith(b"\x89PNG")
    svg = chem.depict("NC(=O)c1ccccc1", fmt="svg")
    assert b"<svg" in svg


def test_similarity_fingerprint_round_trip():
    a = chem.similarity_fingerprint(chem.prepare("NC(=O)c1ccccc1").mol)
    b = chem.similarity_fingerprint(chem.prepare("NC(=O)c1cccc(N)c1").mol)
    same, other = chem.tanimoto_many(a, [a, b])
    assert same == 1.0
    assert 0 < other < 1


# ---- must stay identical to prepare() in model/predict.py and the PARP1_model_colab notebook ----

def _notebook_prepare(smiles):
    chooser = rdMolStandardize.LargestFragmentChooser()
    uncharger = rdMolStandardize.Uncharger()
    normalizer = rdMolStandardize.Normalizer()
    taut = rdMolStandardize.TautomerEnumerator()
    taut.SetMaxTautomers(200)
    m = Chem.MolFromSmiles(smiles)
    if m is None:
        return None
    try:
        m = taut.Canonicalize(uncharger.uncharge(normalizer.normalize(chooser.choose(m))))
    except Exception:
        pass
    return m


@pytest.mark.parametrize("smiles", [
    "OC1=NN=C(Cc2ccc(F)c(c2)C(=O)N2CCN(CC2)C(=O)C2CC2)c2ccccc12.Cl",  # olaparib, HCl salt, lactim form
    "CC1(CCCN1)c1nc2c(cccc2[nH]1)C(N)=O",                           # veliparib
    "C[C@@]1(CCCN1)c1nc2c(cccc2[nH]1)C(N)=O",                         # with a stereocentre
    "CC(=O)Oc1ccccc1C(=O)[O-].[Na+]",                                 # carboxylate salt
    "C[N+](C)(C)CCOC(=O)C.[Cl-]",                                     # permanent cation
    "O=C(N1CCOCC1)C2=CC(OCCCC)=NC3=CC=CC=C32",                        # 8F
    "O=C(N1CCCCC1)C(C2=CC=CC=C2N3CCCC)=CC3=O",                        # 16F
    "CS(C)=O.NC(=O)c1ccccc1",                                         # solvate
    "[O-][N+](=O)c1ccc(O)cc1",                                        # nitro group
    "Oc1ccc2c(c1)C(=O)N=C2",                                          # tautomer-rich
])
def test_server_cleaning_matches_the_notebook(smiles):
    ours = chem.standardise(chem.parse(smiles))
    theirs = _notebook_prepare(smiles)
    assert Chem.MolToSmiles(ours) == Chem.MolToSmiles(theirs)
    gen = rdFingerprintGenerator.GetMorganGenerator(radius=3, fpSize=2048)
    assert (gen.GetFingerprintAsNumPy(ours) == gen.GetFingerprintAsNumPy(theirs)).all()
