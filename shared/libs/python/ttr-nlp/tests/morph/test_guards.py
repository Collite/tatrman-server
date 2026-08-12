# SPDX-License-Identifier: Apache-2.0
"""NLS-P7.1 T6 — `ttrnlp.morph` adds no dependency, and no morphology vendor.

The wheel runs in-process inside the engine-free `nlp` front (⚑NLS-D3). The
whole reason the Czech morphology layer is *owned* rather than licensed is that
the alternatives arrive as a dependency: MorphoDiTa, hunspell, a Stanza model.
Any of them appearing under `morph/` would move the front back behind an image
build and put an NC or GPL licence in the provenance chain (Q-6/D-ε, absolute).

Two checks, because they fail differently. The source check catches the import
on the line it was written. The subprocess check catches what actually gets
pulled in — including anything a *transitive* import drags along.

`tests/test_invariants.py` already parametrizes its engine-freedom checks over
every module in the wheel, so `ttrnlp.morph.*` is covered there the moment it
exists. This file is the tighter bar: not "no engine", but *nothing new at all*.
"""

from __future__ import annotations

import ast
import pathlib
import re
import subprocess
import sys
import tomllib
from importlib import metadata

import pytest


def canonical(name: str) -> str:
    """PEP 503 name normalisation — `typing_extensions` == `typing-extensions`."""
    return re.sub(r"[-_.]+", "-", name).lower()

MORPH = pathlib.Path(__file__).resolve().parents[2] / "src" / "ttrnlp" / "morph"
PYPROJECT = pathlib.Path(__file__).resolve().parents[2] / "pyproject.toml"

#: Morphology stacks that must never be a runtime dependency of this module.
BANNED_VENDORS = (
    "stanza",
    "spacy",
    "torch",
    "ufal",
    "morphodita",
    "nametag",
    "hunspell",
    "simplemma",
    "majka",
)


def _declared_mandatory() -> set[str]:
    """Distribution names from `[project.dependencies]`, extras stripped."""
    declared = tomllib.loads(PYPROJECT.read_text())["project"]["dependencies"]
    names = set()
    for spec in declared:
        name = re.split(r"[<>=!\[; ]", spec, maxsplit=1)[0].strip()
        if name:
            names.add(canonical(name))
    return names


def _mandatory_distributions() -> set[str]:
    """Top-level import names the wheel's mandatory dependency set may provide.

    Read from `pyproject.toml` and expanded transitively rather than
    hardcoded, because the check is "`morph/` imports nothing the wheel did not
    *already* require" — so the allowance has to move when the requirement
    does, and it has to include what those requirements themselves pull in
    (pydantic brings `pydantic_core`; gatenlp brings `sortedcontainers`).

    Extras are deliberately not followed. `httpx` is in the `[http]` extra and a
    morph module reaching for it would be a real finding, not an allowance.
    """
    closure: set[str] = set()
    frontier = list(_declared_mandatory())
    while frontier:
        dist = frontier.pop()
        if dist in closure:
            continue
        closure.add(dist)
        try:
            requires = metadata.requires(dist) or []
        except metadata.PackageNotFoundError:  # pragma: no cover — thin env
            continue
        for requirement in requires:
            # `foo>=1 ; extra == "bar"` — an extra's dependency is not ours.
            if ";" in requirement and "extra ==" in requirement:
                continue
            name = re.split(r"[<>=!\[; ]", requirement, maxsplit=1)[0].strip()
            if name:
                frontier.append(canonical(name))

    modules = set()
    for module, dists in metadata.packages_distributions().items():
        if {canonical(d) for d in dists} & closure:
            modules.add(module)
    return modules


def _morph_modules() -> list[str]:
    modules = []
    for path in sorted(MORPH.rglob("*.py")):
        rel = path.relative_to(MORPH.parents[1]).with_suffix("")
        parts = list(rel.parts)
        if parts[-1] == "__init__":
            parts.pop()
        modules.append(".".join(parts))
    return modules


def test_no_morph_module_imports_a_morphology_vendor():
    offenders = []
    for path in sorted(MORPH.rglob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            else:
                continue
            for name in names:
                if name.split(".")[0].lower() in BANNED_VENDORS:
                    offenders.append(f"{path.name}:{node.lineno} imports {name}")
    assert offenders == [], (
        "the owned morphology layer is owned precisely because it needs none of "
        f"these (Q-6/D-ε): {offenders}"
    )


@pytest.mark.parametrize("module", _morph_modules())
def test_importing_a_morph_module_pulls_in_nothing_new(module: str):
    """One fresh interpreter per module; the delta is what it cost to import.

    Anything third-party in that delta must already be a mandatory dependency of
    the wheel. Stdlib is free — `unicodedata`, `dataclasses`, `re` are the whole
    toolkit this module was specified to need.
    """
    code = (
        "import sys, importlib\n"
        "before = set(sys.modules)\n"
        f"importlib.import_module({module!r})\n"
        "delta = sorted({m.split('.')[0] for m in set(sys.modules) - before})\n"
        "print(','.join(delta))\n"
    )
    out = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    )
    delta = {name for name in out.stdout.strip().split(",") if name}
    third_party = {
        name
        for name in delta
        if not name.startswith("_")
        and name not in sys.stdlib_module_names
        and name != "ttrnlp"
    }
    unexpected = sorted(third_party - _mandatory_distributions())
    assert not unexpected, (
        f"importing {module} pulled in {unexpected} — `ttrnlp.morph` adds ZERO "
        "mandatory dependencies (architecture §2)"
    )


def test_the_tokenizer_and_records_are_stdlib_only():
    """The p7-1 Verify grep, as a test.

    ``tokenize.py`` and ``records.py`` are the two modules the verify block
    names: their imports must be stdlib plus `ttrnlp` itself. Written as a test
    rather than left in the task list so it keeps holding after the list is
    closed.
    """
    offenders = []
    for name in ("tokenize.py", "records.py"):
        tree = ast.parse((MORPH / name).read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            else:
                continue
            for imported in names:
                root = imported.split(".")[0]
                if root not in sys.stdlib_module_names and root != "ttrnlp":
                    offenders.append(f"{name}: {imported}")
    assert offenders == [], offenders
