# SPDX-License-Identifier: Apache-2.0
"""The codegen input, and the guard that keeps it current (NLS-P9.3 T1).

`frontend/openapi.json` is what the frontend's TypeScript types are generated
from. It is committed — the codegen must run on a laptop with no database and no
running service — which means it can go stale, and a stale one is worse than
none: the types compile, the component tests pass, and the field the backend
renamed is `undefined` at runtime in a browser nobody is watching.

So the document is rebuilt here and compared. This is the only test in the suite
whose failure is fixed by running a command rather than by changing code.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

SERVICE = Path(__file__).resolve().parents[1]
SNAPSHOT = SERVICE / "frontend" / "openapi.json"

sys.path.insert(0, str(SERVICE / "scripts"))

from dump_openapi import document  # noqa: E402


@pytest.fixture(scope="module")
def committed() -> dict:
    if not SNAPSHOT.is_file():
        pytest.fail(
            f"{SNAPSHOT} is missing — run `just fe-api` (or "
            "`python scripts/dump_openapi.py frontend/openapi.json`)"
        )
    return json.loads(SNAPSHOT.read_text(encoding="utf-8"))


def test_snapshot_is_current(committed):
    """The committed document is the one this code produces."""
    live = document()
    if live != committed:
        live_paths = set(live["paths"])
        old_paths = set(committed["paths"])
        detail = ""
        if live_paths != old_paths:
            detail = (
                f" — added {sorted(live_paths - old_paths)}, "
                f"removed {sorted(old_paths - live_paths)}"
            )
        pytest.fail(
            "frontend/openapi.json is stale: the API changed and the frontend's "
            "generated types did not" + detail + ". Run `just fe-api`."
        )


def test_every_endpoint_is_in_the_document(committed):
    """No endpoint is invisible to codegen.

    The static-file catch-all is `include_in_schema=False` on purpose — it is
    not an API — and it is the only route that may be missing here.
    """
    assert "/v1/queue" in committed["paths"]
    assert "/v1/machine" in committed["paths"]
    assert "/{path:path}" not in committed["paths"]


def test_no_response_generates_as_an_index_signature(committed):
    """Every response body has a named shape.

    `additionalProperties: true` — what a bare `dict[str, object]` return
    annotation produces — generates as `{[key: string]: unknown}`, and a
    component written against that type-checks no matter what it reads. A
    `dict[str, int]` is fine and deliberately not caught: its keys are genuinely
    open (statuses, filenames) and its *values* are typed.

    `/healthz` and `/readyz` are exempt: they are liveness probes, their body is
    for a human reading `curl` output, and nothing generated consumes them.
    """
    probes = {"/healthz", "/readyz"}
    untyped = []
    for path, methods in committed["paths"].items():
        if path in probes:
            continue
        for verb, operation in methods.items():
            ok = operation.get("responses", {}).get("200", {})
            schema = ok.get("content", {}).get("application/json", {}).get("schema")
            if schema is None:
                continue
            if not schema or schema.get("additionalProperties") is True:
                untyped.append(f"{verb.upper()} {path}")
    assert untyped == [], untyped
