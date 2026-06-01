"""Naviglink — living prototype backend.

Vrstva 0 modelu (SignedSubject) přebrána z prototype/21-izomorfie-subjektu.py
a rozšířená pro reálné HTTP prostředí:
  - perzistentní úložiště (SQLite)
  - kanonická serializace pro podpisy mezi browserem a serverem
  - geo-temporal dotaz nad polygony

Žádné zkratky pro produkci — reálné Ed25519 podpisy, žádný mock.
"""

__version__ = "0.1.0"
