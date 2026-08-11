# SPDX-License-Identifier: Apache-2.0
"""The stub resolver (NL-16) — where `org.tatrman.*.v1` comes from.

**What this file cannot test is the thing that was broken.** The failure was a
*packaging* one: `pip install 'ttr-nlp[grpc]'` brought `grpcio` and `protobuf`,
neither of which contains `org.tatrman.nlp.v1`, and the wheel shipped
`src/ttrnlp` only — so the client could not import its own stubs. A source
checkout cannot reproduce that, because `generated/` is on `pythonpath` and the
first import always succeeds. The wheel's *contents* are asserted by
`publish-python.yml`, which is the only place that has an artifact to look at.

What is tested here is the resolution policy, which is what makes shipping them
safe: the consumer's stubs win, the bundled copy is a fallback, and a missing
extra is named rather than surfacing as a ModuleNotFoundError.
"""

from __future__ import annotations

import sys

import pytest

from ttrnlp import proto


def test_a_source_checkout_resolves_the_stubs_it_already_has():
    """`generated/` is on `pythonpath` here, so nothing needs the bundled copy."""
    assert proto.nlp_pb2().DESCRIPTOR.package == "org.tatrman.nlp.v1"
    assert proto.common_pb2().DESCRIPTOR.package == "org.tatrman.common.v1"
    assert hasattr(proto.nlp_pb2_grpc(), "NlpServiceStub")


def test_the_bundled_copy_is_never_prepended_to_sys_path(monkeypatch):
    """Appended, so an existing `org.tatrman.nlp.v1` keeps winning.

    Two importable copies of one generated module is worse than either: protobuf
    registers descriptors by proto *file name* in a process-global pool, and
    importing both raises a duplicate-registration error naming a file the
    consumer has never heard of. `services/nlp` has its own generated tree and
    installs this wheel — that pair has to keep working.
    """
    monkeypatch.setattr(sys, "path", list(sys.path))
    before = list(sys.path)
    proto.ensure_bundled_stubs()

    assert sys.path[: len(before)] == before


def test_ensure_bundled_stubs_is_idempotent(monkeypatch):
    monkeypatch.setattr(sys, "path", list(sys.path))
    proto.ensure_bundled_stubs()
    once = list(sys.path)
    proto.ensure_bundled_stubs()

    assert sys.path == once


def test_a_missing_grpcio_names_the_extra(monkeypatch):
    """The bug this replaces: `NlpClient.__init__` did a bare `import grpc`
    before anything checked, so a core-only install got `No module named 'grpc'`
    and no hint of which extra provides it."""
    real_import = __import__

    def no_grpc(name, *args, **kwargs):
        if name == "grpc" or name.startswith("grpc."):
            raise ImportError("No module named 'grpc'")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr("builtins.__import__", no_grpc)

    with pytest.raises(ImportError) as raised:
        proto.require_grpc()
    assert "ttr-nlp[grpc]" in str(raised.value)


def test_unresolvable_stubs_name_the_extra_and_the_script(monkeypatch):
    """The other half of the message: the extra for a consumer, the generation
    script for anyone in a checkout."""
    monkeypatch.setattr(proto, "BUNDLED_STUB_ROOT", proto.BUNDLED_STUB_ROOT / "nope")

    real_import = __import__

    def no_stubs(name, *args, **kwargs):
        if name.startswith("org.tatrman"):
            raise ImportError(f"No module named {name!r}")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr("builtins.__import__", no_stubs)

    with pytest.raises(ImportError) as raised:
        proto.nlp_pb2()
    assert "ttr-nlp[grpc]" in str(raised.value)
    assert "gen_proto.py" in str(raised.value)
