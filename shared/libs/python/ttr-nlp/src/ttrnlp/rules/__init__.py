# SPDX-License-Identifier: Apache-2.0
"""The JAPE-class rule engine (NL-1, NL-13).

An own declarative YAML DSL with JAPE-shaped semantics, compiled onto PAMPAC.
Read ``dsl.py``'s module docstring for the vocabulary — it is the reference an
analyst needs, and ``pydoc ttrnlp.rules.dsl`` is how they will read it.

Public surface (contracts §6)::

    load_pack(path_or_str) -> PackModel      parse + schema-validate ONE pack
    check_pack(pack)       -> PackModel      the cross-checks (NLS-PACK-002)
    normalize_pack(pack)   -> PackModel      sugar -> canonical form
    compile_pack(pack)     -> CompiledPack   (NLS-P1.2)
    run_phases(doc, packs) -> PhaseReport    (NLS-P1.2)

The usual sequence is load -> check -> normalize; ``prepare_pack`` does the
three in order, which is what every caller actually wants.
"""

from __future__ import annotations

from pathlib import Path

from ttrnlp.rules.checks import check_pack
from ttrnlp.rules.dsl import PackModel, load_pack, load_schema
from ttrnlp.rules.normalize import normalize_pack


def prepare_pack(path_or_str: str | Path, *, source: str = "") -> PackModel:
    """Load, cross-check and normalise a pack — the whole front end.

    Raises:
        PackError: ``NLS-PACK-001`` if it does not parse, ``NLS-PACK-002`` if it
            parses but its parts do not refer to each other correctly.
    """
    pack = load_pack(path_or_str, source=source)
    check_pack(pack, source=source)
    return normalize_pack(pack)


__all__ = [
    "PackModel",
    "check_pack",
    "load_pack",
    "load_schema",
    "normalize_pack",
    "prepare_pack",
]
