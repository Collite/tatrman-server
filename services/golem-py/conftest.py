# SPDX-License-Identifier: Apache-2.0
"""Ensure the generated proto stubs exist before test collection.

The `org.tatrman.*.v1` Python stubs live under `generated/` and are gitignored
(regenerated from the shared `.proto` source). Generating them here keeps
`uv run pytest` self-contained — the same bootstrap nlp uses.
"""

from __future__ import annotations

from pathlib import Path

_SERVICE_DIR = Path(__file__).resolve().parent
_MARKER = (
    _SERVICE_DIR / "generated" / "org" / "tatrman" / "resolver" / "v1" / "resolver_pb2_grpc.py"
)


def _generate() -> None:
    import runpy

    runpy.run_path(str(_SERVICE_DIR / "scripts" / "gen_proto.py"), run_name="__main__")


if not _MARKER.exists():  # pragma: no cover - one-time bootstrap
    try:
        _generate()
    except SystemExit:
        pass
