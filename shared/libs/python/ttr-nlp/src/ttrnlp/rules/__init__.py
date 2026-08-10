# SPDX-License-Identifier: Apache-2.0
"""The JAPE-class rule engine (NL-1, NL-13).

An own declarative YAML DSL with JAPE-shaped semantics, compiled onto PAMPAC.
Read ``dsl.py``'s module docstring for the vocabulary — it is the reference an
analyst needs, and ``pydoc ttrnlp.rules.dsl`` is how they will read it.

Public surface (contracts §6)::

    load_pack(path_or_str) -> PackModel      parse + schema-validate ONE pack
    check_pack(pack)       -> PackModel      the cross-checks (NLS-PACK-002)
    normalize_pack(pack)   -> PackModel      sugar -> canonical form
    compile_pack(pack)     -> CompiledPack   PAMPAC parser trees
    run_phases(doc, packs) -> PhaseReport    run phases over one Document

The usual sequence is load -> check -> normalize -> compile; ``build_pack`` does
all four, which is what every caller actually wants.
"""

from __future__ import annotations

from pathlib import Path

from ttrnlp.rules.checks import check_pack
from ttrnlp.rules.compiler import CompiledPack, compile_pack
from ttrnlp.rules.dsl import PackModel, load_pack, load_schema
from ttrnlp.rules.executor import CONTROL_STYLES, Firing, run_rules
from ttrnlp.rules.normalize import normalize_pack
from ttrnlp.rules.pipeline import PhaseReport, PhaseTrace, run_phase, run_phases


def prepare_pack(path_or_str: str | Path, *, source: str = "") -> PackModel:
    """Load, cross-check and normalise a pack — the front end, without compiling.

    Raises:
        PackError: ``NLS-PACK-001`` if it does not parse, ``NLS-PACK-002`` if it
            parses but its parts do not refer to each other correctly.
    """
    pack = load_pack(path_or_str, source=source)
    check_pack(pack, source=source)
    return normalize_pack(pack)


def build_pack(path_or_str: str | Path, *, source: str = "") -> CompiledPack:
    """Load, check, normalise and compile a pack — everything, in order."""
    prepared = prepare_pack(path_or_str, source=source)
    return compile_pack(prepared, source=source, normalize=False)


__all__ = [
    "CONTROL_STYLES",
    "CompiledPack",
    "Firing",
    "PackModel",
    "PhaseReport",
    "PhaseTrace",
    "build_pack",
    "check_pack",
    "compile_pack",
    "load_pack",
    "load_schema",
    "normalize_pack",
    "prepare_pack",
    "run_phase",
    "run_phases",
    "run_rules",
]
