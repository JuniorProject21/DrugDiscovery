"""Compound names from PubChem (domain layer).

Looks compounds up by InChIKey, a one-way hash of the structure, so the SMILES of an unpublished
compound never leaves the server. Compounds that aren't in PubChem simply have no name.
"""

import logging
import threading
from collections import OrderedDict
from dataclasses import dataclass

import httpx

log = logging.getLogger(__name__)

PUBCHEM_URL = "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/inchikey/{key}/property/Title,IUPACName/JSON"
# PubChem titles longer than this are usually systematic names or vendor codes, not names people use.
MAX_COMMON_NAME = 40


@dataclass(frozen=True)
class CompoundNames:
    common_name: str | None  # e.g. "Olaparib"
    iupac_name: str | None
    pubchem_cid: int | None

    @property
    def found(self) -> bool:
        return self.pubchem_cid is not None


NOT_IN_PUBCHEM = CompoundNames(None, None, None)


class PubChemNamer:
    def __init__(self, *, enabled: bool = True, timeout: float = 4.0, client: httpx.Client | None = None,
                 cache_size: int = 4096):
        self.enabled = enabled
        self._client = client or httpx.Client(timeout=timeout, headers={"User-Agent": "InhibitAI/1.0"})
        self._cache: OrderedDict[str, CompoundNames] = OrderedDict()
        self._cache_size = cache_size
        self._lock = threading.Lock()

    def lookup(self, inchikey: str | None) -> CompoundNames | None:
        """Names for this InChIKey; NOT_IN_PUBCHEM if PubChem doesn't know it.

        Returns None when PubChem couldn't be asked (lookups off, offline, PubChem down), so callers
        can try again later instead of recording "no name".
        """
        if not self.enabled or not inchikey:
            return None
        with self._lock:
            if inchikey in self._cache:
                self._cache.move_to_end(inchikey)
                return self._cache[inchikey]
        try:
            response = self._client.get(PUBCHEM_URL.format(key=inchikey))
        except httpx.HTTPError as exc:
            log.warning("PubChem lookup failed for %s: %s", inchikey, exc)
            return None
        if response.status_code == 404:
            names = NOT_IN_PUBCHEM
        elif response.status_code == 200:
            names = self._parse(response.json())
        else:
            log.warning("PubChem answered %s for %s", response.status_code, inchikey)
            return None
        with self._lock:
            self._cache[inchikey] = names
            if len(self._cache) > self._cache_size:
                self._cache.popitem(last=False)
        return names

    @staticmethod
    def _parse(payload: dict) -> CompoundNames:
        try:
            first = payload["PropertyTable"]["Properties"][0]
        except (KeyError, IndexError, TypeError):
            return NOT_IN_PUBCHEM
        title = (first.get("Title") or "").strip()
        iupac = (first.get("IUPACName") or "").strip() or None
        common = title if 0 < len(title) <= MAX_COMMON_NAME else None
        return CompoundNames(common_name=common, iupac_name=iupac, pubchem_cid=first.get("CID"))
