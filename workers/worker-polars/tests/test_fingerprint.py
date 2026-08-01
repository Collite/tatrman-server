# SPDX-License-Identifier: Apache-2.0
"""Fork Stage 3.4 T2 — cross-engine schema-fingerprint pin.

Polars recomputes the canonical fingerprint of every shared reference Arrow
IPC fixture and asserts it equals the pinned value in `fingerprints.json`. The
same fixtures are checked by Mssql (Kotlin) and Charon (Integrity.kt); all
implementations reading the same bytes must produce the same digest — the
"same algorithm, multiple implementations, must agree" CI pin.

If this fails after a deliberate algorithm change, regenerate the fixtures with
`shared/testdata/fingerprints/generate.py` (and update every implementation).
"""
import json
from pathlib import Path

import pyarrow as pa
import pyarrow.ipc as ipc
import pytest

from workers_polars.fingerprint import schema_fingerprint

_FIXTURE_DIR = Path(__file__).resolve().parents[3] / "shared" / "testdata" / "fingerprints"
_EXPECTED: dict[str, str] = json.loads((_FIXTURE_DIR / "fingerprints.json").read_text())

# Charon already ships this exact digest for its reference schema — pinning it
# here proves Polars's algorithm is identical to Charon's, not merely
# self-consistent.
_CHARON_REFERENCE_DIGEST = "69779ea65b0e127c59dc4f537bc33f62f08835c0098dbf313d61b35955fea7b8"


def _schema_of(path: Path) -> pa.Schema:
    with ipc.open_stream(pa.OSFile(str(path), "rb")) as reader:
        return reader.schema


@pytest.mark.parametrize("fixture", sorted(_EXPECTED))
def test_fingerprint_matches_shared_pin(fixture: str) -> None:
    schema = _schema_of(_FIXTURE_DIR / fixture)
    assert schema_fingerprint(schema) == _EXPECTED[fixture], (
        f"{fixture}: Polars canonical fingerprint diverged from the shared pin"
    )


def test_reference_matches_charon_digest() -> None:
    assert _EXPECTED["reference.arrow"] == _CHARON_REFERENCE_DIGEST
    schema = _schema_of(_FIXTURE_DIR / "reference.arrow")
    assert schema_fingerprint(schema) == _CHARON_REFERENCE_DIGEST


def test_map_is_entries_wrapped() -> None:
    # Cross-engine map agreement depends on the entries-wrapped {key, value}
    # form (the shape Arrow Java exposes). Guard it explicitly.
    from workers_polars.fingerprint import canonical_schema_string

    schema = _schema_of(_FIXTURE_DIR / "map.arrow")
    canonical = canonical_schema_string(schema)
    assert "map_unsorted|null<entries|struct|nonnull<key|utf8|nonnull;value|int32s|null>>" in canonical
