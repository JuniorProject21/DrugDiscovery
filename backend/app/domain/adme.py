"""ADME from the structure alone: physicochemical properties, drug-likeness rules and alerts.

This is deliberately NOT a trained model. The project has one trained model, for PARP-1 activity,
and inventing an ML ADME prediction without the data to back it would be exactly the kind of
result the report warns against. What can be done honestly is what medicinal chemists do on paper:
compute the properties that govern absorption and distribution, check the published drug-likeness
rules, and screen for the structural alerts that flag assay interference and toxicophores.

Every verdict here therefore comes with the numbers and the thresholds that produced it, so the
app can show its working, and the payload is marked `rule-based` so nothing reads as a prediction.

Rules used:
  * Lipinski's rule of five - Lipinski et al., Adv Drug Deliv Rev 1997 (oral absorption)
  * Veber's rules - Veber et al., J Med Chem 2002 (oral bioavailability)
  * PAINS - Baell & Holloway, J Med Chem 2010 (frequent hitters / assay interference)
  * Brenk alerts - Brenk et al., ChemMedChem 2008 (unsuitable fragments for lead series)
  * QED - Bickerton et al., Nat Chem 2012 (quantitative estimate of drug-likeness)
"""

from dataclasses import dataclass
from functools import lru_cache

from rdkit import Chem
from rdkit.Chem import Crippen, Descriptors, QED, rdMolDescriptors
from rdkit.Chem.FilterCatalog import FilterCatalog, FilterCatalogParams

# The four levels the app draws. "good" and "high" are both read as favourable, except for
# toxicity, where low is the good end.
LOW, MODERATE, GOOD, HIGH = "low", "moderate", "good", "high"


@dataclass(frozen=True)
class Rule:
    name: str
    passed: bool
    detail: str

    def as_dict(self) -> dict:
        return {"name": self.name, "passed": self.passed, "detail": self.detail}


@lru_cache(maxsize=1)
def _alert_catalog() -> FilterCatalog:
    """PAINS (A, B and C) plus the Brenk unwanted-fragment list, built once."""
    params = FilterCatalogParams()
    params.AddCatalog(FilterCatalogParams.FilterCatalogs.PAINS)
    params.AddCatalog(FilterCatalogParams.FilterCatalogs.BRENK)
    return FilterCatalog(params)


def properties(mol: Chem.Mol) -> dict:
    """The physicochemical numbers the rules below are built from."""
    return {
        "molecular_weight": round(Descriptors.MolWt(mol), 2),
        "logp": round(Crippen.MolLogP(mol), 2),
        "tpsa": round(rdMolDescriptors.CalcTPSA(mol), 2),
        "hbd": rdMolDescriptors.CalcNumHBD(mol),
        "hba": rdMolDescriptors.CalcNumHBA(mol),
        "rotatable_bonds": rdMolDescriptors.CalcNumRotatableBonds(mol),
        "aromatic_rings": rdMolDescriptors.CalcNumAromaticRings(mol),
        "heavy_atoms": mol.GetNumHeavyAtoms(),
        "fraction_csp3": round(rdMolDescriptors.CalcFractionCSP3(mol), 3),
        "qed": round(QED.qed(mol), 3),
    }


def _lipinski(p: dict) -> tuple[Rule, int]:
    broken = [
        f"MW {p['molecular_weight']} > 500" if p["molecular_weight"] > 500 else None,
        f"logP {p['logp']} > 5" if p["logp"] > 5 else None,
        f"{p['hbd']} H-bond donors > 5" if p["hbd"] > 5 else None,
        f"{p['hba']} H-bond acceptors > 10" if p["hba"] > 10 else None,
    ]
    broken = [b for b in broken if b]
    # The rule itself allows one violation.
    return Rule(
        name="Lipinski's rule of five",
        passed=len(broken) <= 1,
        detail="Within all four limits" if not broken else "; ".join(broken),
    ), len(broken)


def _veber(p: dict) -> tuple[Rule, int]:
    broken = [
        f"{p['rotatable_bonds']} rotatable bonds > 10" if p["rotatable_bonds"] > 10 else None,
        f"TPSA {p['tpsa']} > 140" if p["tpsa"] > 140 else None,
    ]
    broken = [b for b in broken if b]
    return Rule(
        name="Veber's rules",
        passed=not broken,
        detail="Within both limits" if not broken else "; ".join(broken),
    ), len(broken)


def _absorption(p: dict, violations: int) -> tuple[str, Rule]:
    """Oral absorption, from how many of the Lipinski and Veber limits the compound breaks."""
    level = HIGH if violations == 0 else MODERATE if violations == 1 else LOW
    detail = {
        HIGH: "Breaks none of the Lipinski or Veber limits, so oral absorption is likely.",
        MODERATE: "Breaks one limit; oral absorption is plausible but worth checking.",
        LOW: f"Breaks {violations} limits, which usually means poor oral absorption.",
    }[level]
    return level, Rule(name="Oral absorption", passed=level != LOW, detail=detail)


def _distribution(p: dict) -> tuple[str, Rule]:
    """How widely it is likely to distribute, judged by the properties that govern crossing
    membranes (and the blood-brain barrier in particular)."""
    crosses_bbb = p["tpsa"] <= 90 and p["molecular_weight"] <= 450 and 1.0 <= p["logp"] <= 4.0
    if crosses_bbb:
        return HIGH, Rule(
            name="Blood-brain barrier",
            passed=True,
            detail=f"TPSA {p['tpsa']} <= 90, MW {p['molecular_weight']} <= 450 and logP {p['logp']} "
                   "in 1-4: small, lipophilic and not very polar, so it may reach the brain.",
        )
    if p["tpsa"] <= 120 and p["logp"] >= 0:
        return MODERATE, Rule(
            name="Blood-brain barrier",
            passed=False,
            detail=f"TPSA {p['tpsa']} and logP {p['logp']}: likely to distribute into tissue, "
                   "but unlikely to cross into the brain.",
        )
    return LOW, Rule(
        name="Blood-brain barrier",
        passed=False,
        detail=f"TPSA {p['tpsa']} and logP {p['logp']}: too polar to cross membranes freely.",
    )


def _metabolism(p: dict) -> tuple[str, Rule]:
    """Metabolic stability, as the liabilities that most often drive clearance: lipophilicity,
    flat aromatic systems and a lack of sp3 character."""
    liabilities = [
        f"logP {p['logp']} > 4 (lipophilic compounds are cleared faster)" if p["logp"] > 4 else None,
        f"{p['aromatic_rings']} aromatic rings (flat, oxidation-prone systems)"
        if p["aromatic_rings"] >= 4 else None,
        f"only {int(p['fraction_csp3'] * 100)}% sp3 carbons" if p["fraction_csp3"] < 0.25 else None,
    ]
    liabilities = [item for item in liabilities if item]
    level = GOOD if not liabilities else MODERATE if len(liabilities) == 1 else LOW
    return level, Rule(
        name="Metabolic stability",
        passed=level != LOW,
        detail="No obvious metabolic liabilities in the structure." if not liabilities
               else "Liabilities: " + "; ".join(liabilities),
    )


def _toxicity(mol: Chem.Mol) -> tuple[str, list[dict], Rule]:
    """Structural alerts: fragments known to interfere with assays (PAINS) or to be unsuitable in
    a lead series (Brenk). An alert is a warning to look closer, not a finding of toxicity."""
    def catalog_of(entry) -> str:
        properties = list(entry.GetPropList())
        return entry.GetProp("FilterSet") if "FilterSet" in properties else "alert"

    alerts = [{"catalog": catalog_of(m), "description": m.GetDescription()}
              for m in _alert_catalog().GetMatches(mol)]
    pains = [a for a in alerts if "pains" in a["catalog"].lower()]
    if pains:
        level = HIGH
    elif len(alerts) >= 3:
        level = HIGH
    elif alerts:
        level = MODERATE
    else:
        level = LOW
    detail = ("No PAINS or Brenk structural alerts." if not alerts
              else f"{len(alerts)} structural alert(s): " + "; ".join(a["description"] for a in alerts[:4]))
    return level, alerts, Rule(name="Structural alerts", passed=level == LOW, detail=detail)


def profile(mol: Chem.Mol) -> dict:
    """The whole ADME picture for one structure, as the JSON the API and app pass around.

    `absorption`, `distribution`, `metabolism` and `toxicity` are the four headline levels; every
    one of them is backed by the numbers in `properties` and the thresholds in `rules`.
    """
    p = properties(mol)
    lipinski, lipinski_broken = _lipinski(p)
    veber, veber_broken = _veber(p)
    absorption, absorption_rule = _absorption(p, lipinski_broken + veber_broken)
    distribution, distribution_rule = _distribution(p)
    metabolism, metabolism_rule = _metabolism(p)
    toxicity, alerts, toxicity_rule = _toxicity(mol)
    return {
        # Marks the whole payload as computed from the structure, never as a model prediction.
        "method": "rule-based",
        "absorption": absorption,
        "distribution": distribution,
        "metabolism": metabolism,
        "toxicity": toxicity,
        "properties": p,
        "rules": [r.as_dict() for r in
                  (lipinski, veber, absorption_rule, distribution_rule, metabolism_rule, toxicity_rule)],
        "alerts": alerts,
        "summary": (
            f"Drug-likeness (QED) {p['qed']}. " +
            ("Passes Lipinski and Veber. " if lipinski.passed and veber.passed
             else "Breaks a drug-likeness limit. ") +
            ("No structural alerts." if not alerts else f"{len(alerts)} structural alert(s).")
        ),
    }


def profile_from_smiles(smiles: str) -> dict | None:
    mol = Chem.MolFromSmiles(smiles)
    return profile(mol) if mol is not None else None
