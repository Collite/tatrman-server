# SPDX-License-Identifier: Apache-2.0
"""Generate golem-py's Python proto stubs (messages + gRPC service stubs).

golem-py owns its `org.tatrman.*.v1` stubs rather than consuming the `shared-proto`
Python package, for the reason nlp already records: that package's Python lane emits
message types only (no gRPC service stub). The canonical source stays the shared
`.proto` tree — this script compiles the files golem-py needs with grpcio-tools'
bundled protoc + grpc_python plugin.

⚑ Clean-room note (PROVENANCE.md): generating stubs from a SHARED proto is contract
conformance, not code reuse. The proto IS the interface both sides implement, and it
is on the MAY-consult list.

Run:  uv run python scripts/gen_proto.py
Output: services/golem-py/generated/org/tatrman/{resolver,nlp,common}/v1/*.py(i)
"""

from __future__ import annotations

import sys
from pathlib import Path

from grpc_tools import protoc

SERVICE_DIR = Path(__file__).resolve().parent.parent
# services/golem-py -> repo root (tatrman-server)
REPO_ROOT = SERVICE_DIR.parent.parent
PROTO_ROOT = REPO_ROOT / "shared" / "proto" / "src" / "main" / "proto"
OUT_DIR = SERVICE_DIR / "generated"

# resolver.proto is the door (`Resolve` + the RV-P2.4 `Gate` sibling); it imports
# nlp.proto for the parse passthrough, which in turn imports common. Imported files
# must be generated too — protoc only emits for the files it is given.
PROTOS = [
    "org/tatrman/resolver/v1/resolver.proto",
    "org/tatrman/nlp/v1/nlp.proto",
    "org/tatrman/common/v1/response_message.proto",
]


def _ensure_namespace_inits(out_dir: Path) -> None:
    """protoc emits per-package dirs but no `__init__.py`; add them so the
    `org.tatrman.*.v1` packages import cleanly under pytest."""
    for proto in PROTOS:
        parts = Path(proto).parent.parts  # ('org','tatrman','resolver','v1')
        cur = out_dir
        for part in parts:
            cur = cur / part
            init = cur / "__init__.py"
            if not init.exists():
                init.write_text("# generated namespace package\n", encoding="utf-8")


def main() -> int:
    if not PROTO_ROOT.exists():
        print(f"proto root not found: {PROTO_ROOT}", file=sys.stderr)
        return 2
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    args = [
        "grpc_tools.protoc",
        f"-I{PROTO_ROOT}",
        f"--python_out={OUT_DIR}",
        f"--pyi_out={OUT_DIR}",
        f"--grpc_python_out={OUT_DIR}",
        *PROTOS,
    ]
    rc: int = protoc.main(args)
    if rc != 0:
        print(f"protoc failed with code {rc}", file=sys.stderr)
        return rc
    _ensure_namespace_inits(OUT_DIR)
    print(f"generated proto stubs → {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
