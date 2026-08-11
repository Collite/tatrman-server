# SPDX-License-Identifier: Apache-2.0
"""Generate the `org.tatrman.*.v1` stubs at build time and ship them (NL-16).

The wheel's `[grpc]` extra brings `grpcio` and `protobuf`. Neither of them
contains `org.tatrman.nlp.v1` — that is generated from `shared/proto`, and until
this hook existed it was generated only into a gitignored `generated/` tree that
`packages = ["src/ttrnlp"]` never shipped. So `pip install 'ttr-nlp[grpc]'`
installed a client that could not import its own stubs, which is exactly the
consumer NL-16 is for (`nlp-mcp`, the DFP model-validator).

**Still not committed.** `shared/proto` stays the single canonical source and the
generated tree stays out of git; the hook runs the same `scripts/gen_proto.py`
the tests bootstrap with, then stages a clean copy into the archive at
`ttrnlp/_proto`. What changed is only that the build produces them — a wheel
built from this tree carries stubs generated from the `.proto` beside it, which
is a stronger guarantee than a committed copy anyone could forget to regenerate.

**Under `ttrnlp/_proto`, not at the archive root.** A top-level `org/` package
from this distribution would collide in site-packages with any other one that
ships the same namespace (`services/nlp` generates its own). Kept inside the
package, they are reachable only through `ttrnlp.proto`, which appends the
directory to `sys.path` and only when nothing else already provides them.
"""

from __future__ import annotations

import importlib.util
import shutil
import tempfile
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

WHEEL_DIR = Path(__file__).resolve().parent
GENERATED = WHEEL_DIR / "generated"
STUB_MARKER = GENERATED / "org" / "tatrman" / "nlp" / "v1" / "nlp_pb2_grpc.py"

#: Where the stubs land inside the wheel. `ttrnlp.proto.BUNDLED_STUB_ROOT` reads
#: the same path from the installed side — the two must not drift.
ARCHIVE_PATH = "ttrnlp/_proto"


def _generate_stubs() -> int:
    """Run `scripts/gen_proto.py`'s `main()` in-process. Returns its exit code."""
    path = WHEEL_DIR / "scripts" / "gen_proto.py"
    spec = importlib.util.spec_from_file_location("_ttrnlp_gen_proto", path)
    if spec is None or spec.loader is None:  # pragma: no cover - unreachable
        raise RuntimeError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return int(module.main())


class CustomBuildHook(BuildHookInterface):
    PLUGIN_NAME = "custom"

    def initialize(self, version: str, build_data: dict) -> None:
        if self.target_name != "wheel":
            return

        if not STUB_MARKER.exists():
            # `grpc_tools` comes from `[build-system] requires`, so this runs in
            # the isolated build env without the dev group being installed.
            #
            # Loaded and called, NOT run as `__main__`: the script ends in
            # `raise SystemExit(main())`, and a SystemExit(0) escaping a build
            # hook exits the backend process cleanly enough that hatchling never
            # writes its result file — the front-end then fails with a missing
            # `build_wheel.txt` and no mention of this hook at all.
            rc = _generate_stubs()
            if rc:
                raise RuntimeError(f"scripts/gen_proto.py failed with code {rc}")
        if not STUB_MARKER.exists():
            raise RuntimeError(
                f"proto stub generation produced no {STUB_MARKER.name} — the "
                "wheel would install a gRPC client that cannot import its own "
                "stubs, so the build stops here rather than shipping it"
            )

        # A clean copy: `generated/` accumulates `__pycache__` from local test
        # runs, and force_include ships a directory whole.
        self._staged = Path(tempfile.mkdtemp(prefix="ttr-nlp-proto-"))
        shutil.copytree(
            GENERATED / "org",
            self._staged / "org",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
        )
        build_data["force_include"][str(self._staged / "org")] = f"{ARCHIVE_PATH}/org"

    def finalize(self, version: str, build_data: dict, artifact_path: str) -> None:
        staged = getattr(self, "_staged", None)
        if staged is not None:
            shutil.rmtree(staged, ignore_errors=True)
            self._staged = None
