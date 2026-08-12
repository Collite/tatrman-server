# SPDX-License-Identifier: Apache-2.0
"""Ensure the generated proto stubs exist before test collection.

The `org.tatrman.{nlp,common}.v1` Python stubs live under `generated/` and are
gitignored (regenerated from the shared `.proto` source). Generating them here
keeps `uv run pytest` self-contained — matching the README's "regenerates proto
first" contract — without a separate manual step.

⛑ **The two load-bearing details below were predicted here and then hit for real.**
`services/golem-py/conftest.py` carried a note saying this file had the same shape
and therefore the same latent bug, never triggered *because nothing ever ran this
suite in CI*. NLS-P3.3 added the `nlp` job — and the suite has been red on master
from that very commit (`5441855`, the #42 merge) with
`ModuleNotFoundError: No module named 'org'` on four test modules whose stubs are
plainly on disk. Fixed here at RV-P8.3 because a permanently red job is exactly
how a real regression hides.

1. **`gen_proto.py` ends in `raise SystemExit(main())`**, so
   `runpy.run_path(..., run_name="__main__")` raises `SystemExit` even on SUCCESS.
   Anything after that call in the same `try` never runs — hence the `finally`.
   The exit CODE is the only thing separating success from a protoc failure, so a
   non-zero one is re-raised rather than swallowed: swallowing it turns a real
   codegen failure into the same misleading `No module named 'org'`.
2. **pytest's `pythonpath` ini option puts `generated/` on `sys.path` BEFORE this
   conftest is imported.** On a clean checkout that directory does not exist yet,
   so CPython caches a null importer for it in `sys.path_importer_cache`, and
   creating the directory a moment later does NOT invalidate that cache. Every
   later `from org.tatrman…` then fails on a machine where the files are there.
   `importlib.invalidate_caches()` is what makes the freshly written stubs
   visible — and it must run whether or not codegen raised.
"""

from __future__ import annotations

import importlib
from pathlib import Path

_SERVICE_DIR = Path(__file__).resolve().parent
_MARKER = _SERVICE_DIR / "generated" / "org" / "tatrman" / "nlp" / "v1" / "nlp_pb2_grpc.py"


def _generate() -> None:
    import runpy

    try:
        runpy.run_path(str(_SERVICE_DIR / "scripts" / "gen_proto.py"), run_name="__main__")
    except SystemExit as exc:  # protoc reports success AND failure this way
        if exc.code:
            raise
    finally:
        # See (1) and (2): this MUST run even though the call always raises.
        importlib.invalidate_caches()


if not _MARKER.exists():  # pragma: no cover - one-time bootstrap
    _generate()
