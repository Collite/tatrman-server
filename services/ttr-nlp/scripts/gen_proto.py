# SPDX-License-Identifier: Apache-2.0
"""Generate ttr-nlp's Python proto stubs (messages + gRPC service).

ttr-nlp owns its `org.tatrman.{nlp,common}.v1` stubs rather than consuming the
`shared-proto` Python package, because that package's Python lane emits message
types only (no gRPC service stub) and would collide on the `org.tatrman.nlp.v1`
package. The canonical source stays the shared `.proto` tree; this script just
compiles the two files ttr-nlp needs (nlp.proto + its common import) with
grpcio-tools' bundled protoc + grpc_python plugin.

Run:  uv run python scripts/gen_proto.py
Output: services/ttr-nlp/generated/org/tatrman/{nlp,common}/v1/*.py(i)
"""

from __future__ import annotations

import sys
from pathlib import Path

from grpc_tools import protoc

SERVICE_DIR = Path(__file__).resolve().parent.parent
# services/ttr-nlp -> repo root (tatrman-server)
REPO_ROOT = SERVICE_DIR.parent.parent
PROTO_ROOT = REPO_ROOT / "shared" / "proto" / "src" / "main" / "proto"
OUT_DIR = SERVICE_DIR / "generated"

PROTOS = [
    "org/tatrman/nlp/v1/nlp.proto",
    "org/tatrman/common/v1/response_message.proto",
]


def _ensure_namespace_inits(out_dir: Path) -> None:
    """protoc emits per-package dirs but no `__init__.py`; add them so the
    `org.tatrman.*.v1` packages import cleanly under pytest/uvicorn."""
    for proto in PROTOS:
        pkg_dir = out_dir / Path(proto).parent
        parts = Path(proto).parent.parts  # ('org','tatrman','nlp','v1')
        cur = out_dir
        for part in parts:
            cur = cur / part
            init = cur / "__init__.py"
            if not init.exists():
                init.write_text("# generated namespace package\n", encoding="utf-8")
        assert pkg_dir.exists(), pkg_dir


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
    rc = protoc.main(args)
    if rc != 0:
        print(f"protoc failed with code {rc}", file=sys.stderr)
        return rc
    _ensure_namespace_inits(OUT_DIR)
    print(f"generated proto stubs → {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
