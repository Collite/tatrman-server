# SPDX-License-Identifier: Apache-2.0
"""P4.1·T6 — proposer-not-binder (RV-7), enforced STRUCTURALLY.

The Python port of the server's `SingleBinderTest`. That test earned its keep on the
day `resolve.gate:v1` landed: a third file could suddenly build a `Binding`, and the
fence failed before a human noticed.

The rule here is narrower than "do not construct a Binding", because two places must:
the mappers that read one off the wire. So the fence is **where**, not whether — a
`Binding(...)` may only be minted inside `core_client.py`, which is the single door
through which the core's and the gate's answers enter this process.
"""

from __future__ import annotations

import ast
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src" / "golem_py"

# The ONLY module allowed to construct a `Binding`: it maps one off the wire, from a
# response the core or the gate produced. Everything else must obtain bindings from it.
ALLOWED_MODULES = {"core_client.py"}


def _binding_constructions(path: Path) -> list[int]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return [
        node.lineno
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "Binding"
    ]


def test_no_module_but_the_wire_mapper_constructs_a_binding() -> None:
    offenders = {
        path.name: lines
        for path in sorted(SRC.glob("*.py"))
        if path.name not in ALLOWED_MODULES and (lines := _binding_constructions(path))
    }

    assert offenders == {}, (
        "a binding was constructed outside the wire mapper: "
        f"{offenders} — bindings only ever arrive from `resolve.bind` or `resolve.gate` "
        "(RV-7 proposer-not-binder)"
    )


def test_the_mapper_itself_still_constructs_exactly_where_expected() -> None:
    """The negative fence is worthless if the allowlist drifts to cover a file that no
    longer maps anything. Assert the allowed site is still a real one."""
    assert _binding_constructions(SRC / "core_client.py"), (
        "core_client no longer constructs a Binding — either the mapper moved (update "
        "ALLOWED_MODULES) or this fence is now guarding nothing"
    )


def test_no_module_mints_a_resume_token() -> None:
    """RS-26 / P0-3 T5: the token is the CORE's. Signing an option set agent-side would
    let the agent fabricate "the user chose X" — so the agent may store and return a
    token, never produce one."""
    minting = []
    for path in sorted(SRC.glob("*.py")):
        text = path.read_text(encoding="utf-8")
        for lineno, line in enumerate(text.splitlines(), start=1):
            if "hmac" in line.lower() or "resume_token=" in line and "state." not in line:
                if line.strip().startswith("#") or '"""' in line:
                    continue
                minting.append(f"{path.name}:{lineno}")
    assert minting == [], f"a resume token may be stored, never minted: {minting}"
