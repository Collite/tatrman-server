# SPDX-License-Identifier: Apache-2.0
"""Read the SERVER's lattice goldens as proto and map them with the production mapper.

`services/resolver/src/test/resources/lattice/*.lattice.json` is proto JSON of
`org.tatrman.resolver.v1.ResolutionState` — the goldens the Kotlin core is byte-compared
against (RV-P2.1 T2 / P2.5 T4). Reading THOSE files rather than committing a Python copy
is the point: RV-28 says one corpus, N shells, and a copied fixture is how two shells
start disagreeing about what the core emits.
"""

from __future__ import annotations

import sys
from pathlib import Path

_SERVICE_DIR = Path(__file__).resolve().parents[1]
_GENERATED = _SERVICE_DIR / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

from google.protobuf import json_format  # noqa: E402
from org.tatrman.resolver.v1 import resolver_pb2  # noqa: E402

from golem_py.core_client import lattice_from_proto  # noqa: E402
from golem_py.state import ResolutionState  # noqa: E402

LATTICE_DIR = _SERVICE_DIR.parent / "resolver" / "src" / "test" / "resources" / "lattice"


def golden_path(case: str) -> Path:
    return LATTICE_DIR / f"{case}.lattice.json"


def lattice_proto(case: str) -> resolver_pb2.ResolutionState:
    msg = resolver_pb2.ResolutionState()
    json_format.Parse(golden_path(case).read_text(encoding="utf-8"), msg)
    return msg


def lattice_golden(case: str) -> ResolutionState:
    return lattice_from_proto(lattice_proto(case))
