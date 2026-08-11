# SPDX-License-Identifier: Apache-2.0
"""Where the generated ``org.tatrman.*.v1`` stubs come from.

Three modules need them — the serializer, the client, and the client's
diagnostic flattening — and until this module existed each resolved them with
its own bare ``from org.tatrman.nlp.v1 import …``. That worked in a source
checkout, where ``generated/`` is on ``pythonpath``, and nowhere else: the wheel
ships ``src/ttrnlp`` only, so the ``[grpc]`` extra installed ``grpcio`` and
``protobuf`` and still left every import above unsatisfiable. A pip-installing
consumer (``nlp-mcp``, the DFP model-validator — NL-16 names both) got an
ImportError telling it to run a script from a checkout it does not have.

**So the wheel now carries the stubs, and this module finds them.** They are
generated at build time into ``ttrnlp/_proto`` by ``hatch_build.py`` — still not
committed, and ``shared/proto`` is still the one canonical source; what changed
is that "not committed" no longer means "not shipped".

**The bundled copy is appended to ``sys.path``, never prepended.** A process that
already has ``org.tatrman.nlp.v1`` importable keeps its own: the service and its
tests put ``services/nlp/generated`` on the path, and that tree is generated from
the same ``.proto`` but is not the only thing in it. Two importable copies of one
generated module would be worse than either — protobuf registers descriptors by
proto *file name* in a process-global pool, so importing both raises a duplicate
registration error that names a file nobody has heard of. Appending means there
is exactly one, and it is the consumer's if the consumer has one.
"""

from __future__ import annotations

import sys
from pathlib import Path
from types import ModuleType

#: The stubs the wheel carries. Absent in a source checkout that has not run
#: `scripts/gen_proto.py` — which is fine, because there `generated/` is on
#: `pythonpath` and the first import attempt already succeeded.
BUNDLED_STUB_ROOT = Path(__file__).resolve().parent / "_proto"

_MISSING_STUBS = (
    "the org.tatrman.nlp.v1 proto stubs are not available — install the gRPC "
    "extra (`pip install 'ttr-nlp[grpc]'`); in a source checkout run "
    "`uv run python scripts/gen_proto.py`"
)

_MISSING_GRPC = (
    "this needs the gRPC extra — install `ttr-nlp[grpc]` (it brings `grpcio` "
    "and `protobuf`)"
)


def ensure_bundled_stubs() -> bool:
    """Put the bundled stubs on ``sys.path``, once. True if they are there.

    Idempotent and cheap to call on every miss: the membership test is the whole
    guard, and the miss only happens on the first import of a process anyway.
    """
    if not BUNDLED_STUB_ROOT.is_dir():
        return False
    root = str(BUNDLED_STUB_ROOT)
    if root not in sys.path:
        sys.path.append(root)
    return True


def _resolve(name: str, attr: str) -> ModuleType:
    """Import ``attr`` from generated package ``name``, bundled copy as fallback."""
    def attempt() -> ModuleType:
        module = __import__(name, fromlist=[attr])
        return getattr(module, attr)

    try:
        return attempt()
    except ImportError:
        pass
    if ensure_bundled_stubs():
        try:
            return attempt()
        except ImportError as exc:
            raise ImportError(_MISSING_STUBS) from exc
    raise ImportError(_MISSING_STUBS)


def require_grpc() -> ModuleType:
    """The ``grpc`` module, or the message naming the extra that provides it.

    Checked before anything imports ``nlp_pb2_grpc``, because that module imports
    ``grpc`` itself: without this the caller gets ``No module named 'grpc'`` and
    has to work out on their own which extra they were supposed to install.
    """
    try:
        import grpc
    except ImportError as exc:
        raise ImportError(_MISSING_GRPC) from exc
    return grpc


def nlp_pb2() -> ModuleType:
    """The nlp message module — all the serializer needs (no ``grpcio``)."""
    return _resolve("org.tatrman.nlp.v1", "nlp_pb2")


def nlp_pb2_grpc() -> ModuleType:
    """The nlp service stub module. Needs ``grpcio``."""
    require_grpc()
    return _resolve("org.tatrman.nlp.v1", "nlp_pb2_grpc")


def common_pb2() -> ModuleType:
    """``org.tatrman.common.v1.response_message_pb2`` — the Rule-6 message slot."""
    return _resolve("org.tatrman.common.v1", "response_message_pb2")


__all__ = [
    "BUNDLED_STUB_ROOT",
    "common_pb2",
    "ensure_bundled_stubs",
    "nlp_pb2",
    "nlp_pb2_grpc",
    "require_grpc",
]
