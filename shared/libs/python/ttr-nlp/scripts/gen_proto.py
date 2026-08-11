# SPDX-License-Identifier: Apache-2.0
"""Generate the wheel's `org.tatrman.nlp.v1` stubs (messages + gRPC service).

A mirror of `services/nlp/scripts/gen_proto.py`, deliberately — same canonical
source, same output layout, same gitignored-generated posture. The wheel needs
its own copy because it ships the *client* (NL-16) and the serializer, and it
cannot import the service's `generated/` tree: `ttr-nlp` is installed by
consumers who do not have `services/nlp` at all (`nlp-mcp`, the DFP
model-validator).

**Generated code is not committed and not shipped.** The `[grpc]` extra brings
`grpcio`/`protobuf`; the stubs themselves are produced here from
`shared/proto`, which stays the single canonical source. That is why
`ttrnlp.doc.serialize` imports them lazily and says something useful when they
are absent — a consumer who installed the core wheel and never asked for gRPC
should get an actionable message, not a ModuleNotFoundError from a file they
have never heard of.

Run:  uv run python scripts/gen_proto.py
Output: shared/libs/python/ttr-nlp/generated/org/tatrman/{nlp,common}/v1/*.py(i)
"""

from __future__ import annotations

import sys
from pathlib import Path

from grpc_tools import protoc

WHEEL_DIR = Path(__file__).resolve().parent.parent
# shared/libs/python/ttr-nlp -> repo root (tatrman-server)
REPO_ROOT = WHEEL_DIR.parent.parent.parent.parent
PROTO_ROOT = REPO_ROOT / "shared" / "proto" / "src" / "main" / "proto"
OUT_DIR = WHEEL_DIR / "generated"

PROTOS = [
    "org/tatrman/nlp/v1/nlp.proto",
    "org/tatrman/common/v1/response_message.proto",
]


def _ensure_namespace_inits(out_dir: Path) -> None:
    """protoc emits per-package dirs but no `__init__.py`; add them so the
    `org.tatrman.*.v1` packages import cleanly under pytest."""
    for proto in PROTOS:
        pkg_dir = out_dir / Path(proto).parent
        cur = out_dir
        for part in Path(proto).parent.parts:  # ('org','tatrman','nlp','v1')
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

    rc = protoc.main(
        [
            "grpc_tools.protoc",
            f"-I{PROTO_ROOT}",
            f"--python_out={OUT_DIR}",
            f"--pyi_out={OUT_DIR}",
            f"--grpc_python_out={OUT_DIR}",
            *PROTOS,
        ]
    )
    if rc != 0:
        print(f"protoc failed with code {rc}", file=sys.stderr)
        return rc
    _ensure_namespace_inits(OUT_DIR)
    print(f"generated proto stubs → {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
