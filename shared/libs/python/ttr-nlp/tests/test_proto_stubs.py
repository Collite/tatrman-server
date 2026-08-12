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
from pathlib import Path

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


# ── the build hook's one decision (see hatch_build.py) ───────────────────────


def _load_hook_module():
    """`hatch_build.py` sits at the wheel root, outside `src/` — load it by path."""
    import importlib.util

    path = Path(__file__).resolve().parent.parent / "hatch_build.py"
    spec = importlib.util.spec_from_file_location("_ttrnlp_hatch_build", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _hook(module, target: str):
    hook = module.CustomBuildHook.__new__(module.CustomBuildHook)
    type(hook).target_name = property(lambda self: target)
    return hook


def test_an_editable_build_never_touches_the_proto_tree(monkeypatch):
    """An editable install is not a distribution and must not need `shared/proto`.

    `services/nlp`'s Dockerfile is the case that proves it: its deps stage copies
    the wheel and runs `uv sync --frozen` BEFORE `shared/proto` is copied in, so
    generating stubs there failed the whole image build — for a consumer that
    never wanted the bundled copy, because a checkout that path-depends on this
    wheel has its own `generated/` tree on `pythonpath`.
    """
    module = _load_hook_module()

    def explode():
        raise AssertionError("an editable build must not run gen_proto")

    monkeypatch.setattr(module, "_gen_proto", explode)
    build_data: dict = {"force_include": {}}
    _hook(module, "wheel").initialize("editable", build_data)

    assert build_data["force_include"] == {}


def test_a_real_wheel_build_refuses_without_the_proto_sources(monkeypatch):
    """The other half: a distribution with no stubs is the bug this all exists to
    prevent, so a wheel build that cannot generate them stops rather than ships."""
    module = _load_hook_module()

    class _NoProto:
        PROTO_ROOT = Path("/definitely/not/here")

    monkeypatch.setattr(module, "_gen_proto", lambda: _NoProto())

    with pytest.raises(RuntimeError) as raised:
        _hook(module, "wheel").initialize("standard", {"force_include": {}})
    assert "proto sources" in str(raised.value)


def test_a_non_wheel_target_is_left_alone(monkeypatch):
    module = _load_hook_module()

    def explode():
        raise AssertionError("only the wheel target stages stubs")

    monkeypatch.setattr(module, "_gen_proto", explode)
    _hook(module, "sdist").initialize("standard", {"force_include": {}})
