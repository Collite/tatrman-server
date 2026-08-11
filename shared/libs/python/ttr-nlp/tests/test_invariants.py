# SPDX-License-Identifier: Apache-2.0
"""NLS-P0.T4 — the two invariants the whole suite is built on.

**The pin (NL-2).** `gatenlp==1.0.8` exactly. The compiler and the JAPE-exact
executor are written against PAMPAC internals, so a floor instead of a pin would
let a minor release quietly change what a rule matches.

**Engine-freedom (⚑NLS-D3, RG-P1.S3.T6).** The wheel runs in-process inside the
`nlp` front, and the front must never acquire a model dependency. gatenlp ships
`lib_stanza` / `lib_spacy` convenience importers that `import stanza` / `import
spacy` at module top — so a single innocent-looking import of theirs would drag
torch into a container built to be model-free.

**No scoring in the gazetteer (NL-17, added at NLS-P2.1).** The deterministic
matcher and the fuzzy matchers are separated by a line that is easy to erode one
"just a confidence field" at a time, and the erosion looks like a feature.
"""

from __future__ import annotations

import ast
import pathlib
import subprocess
import sys
import tomllib

import gatenlp
import pytest

from ttrnlp.doc.model import PINNED_GATENLP_VERSION

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "ttrnlp"
PYPROJECT = pathlib.Path(__file__).resolve().parents[1] / "pyproject.toml"

BANNED_MODULES = ("stanza", "spacy", "torch", "gatenlp.lib_stanza", "gatenlp.lib_spacy")


def test_gatenlp_is_pinned_to_the_tested_version():
    assert PINNED_GATENLP_VERSION == "1.0.8"
    assert gatenlp.__version__ == PINNED_GATENLP_VERSION


def test_the_declared_dependency_matches_the_asserted_pin():
    # A constant that drifts from the dependency spec is worse than no constant:
    # the import-time guard would start rejecting the version actually installed.
    deps = tomllib.loads(PYPROJECT.read_text())["project"]["dependencies"]
    assert f"gatenlp=={PINNED_GATENLP_VERSION}" in deps


def _module_names():
    for path in sorted(SRC.rglob("*.py")):
        rel = path.relative_to(SRC.parent).with_suffix("")
        parts = list(rel.parts)
        if parts[-1] == "__init__":
            parts.pop()
        yield ".".join(parts)


def test_no_module_imports_an_engine_even_transitively():
    """Source-level guard.

    This has to be a source check, not a `sys.modules` check: stanza and spaCy
    are not installed here, so an accidental import would surface as a confusing
    ImportError in this suite and as a missing model dependency in production —
    but only for the code path that reached it. Reading the ASTs catches it on
    the line it was written.
    """
    offenders = []
    for path in sorted(SRC.rglob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            else:
                continue
            for name in names:
                root = name.split(".")[0]
                if name in BANNED_MODULES or root in ("stanza", "spacy", "torch"):
                    offenders.append(f"{path.name}:{node.lineno} imports {name}")
    assert offenders == [], (
        "the wheel must stay engine-free (⚑NLS-D3) — build annotations from the "
        f"backends' JSON instead: {offenders}"
    )


#: NL-17. Names that would mean the deterministic gazetteer had grown a scoring
#: dimension — a feature it emits, a threshold it reads, a distance it computes.
SCORING_NAMES = (
    "score",
    "scores",
    "scoring",
    "confidence",
    "threshold",
    "fuzzy",
    "levenshtein",
    "similarity",
    "edit_distance",
)


def _docstring_nodes(tree: ast.AST) -> set[int]:
    """The `id()`s of every Constant that is a docstring, not a value."""
    ids = set()
    for node in ast.walk(tree):
        if isinstance(
            node, ast.Module | ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef
        ):
            body = getattr(node, "body", [])
            if (
                body
                and isinstance(body[0], ast.Expr)
                and isinstance(body[0].value, ast.Constant)
                and isinstance(body[0].value.value, str)
            ):
                ids.add(id(body[0].value))
    return ids


def test_the_gazetteer_has_no_scoring_dimension():
    """NL-17, at the source level.

    The P2.1 verify block asks for `rg -i "score|fuzzy|levenshtein"
    src/ttrnlp/gazetteer/` to come back empty. Taken literally that gate also
    forbids *documenting* the exclusion, which is the wrong trade: those two
    modules explain at length why scoring lives world-side, and a reader who
    cannot find that explanation is the person most likely to add a `score`
    field. So the gate reads the AST instead — prose may name what the code may
    not do, and only identifiers and real string values count.

    A companion assertion lives in `tests/gazetteer/test_annotate.py`
    (`test_no_lookup_carries_a_score`), which checks the emitted annotations
    rather than the source: this test catches the field being computed, that one
    catches it arriving from a list.
    """
    gazetteer_src = SRC / "gazetteer"
    offenders = []
    for path in sorted(gazetteer_src.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        docstrings = _docstring_nodes(tree)
        for node in ast.walk(tree):
            found = ""
            if isinstance(node, ast.Name):
                found = node.id
            elif isinstance(node, ast.Attribute):
                found = node.attr
            elif isinstance(node, ast.arg):
                found = node.arg
            elif isinstance(node, ast.FunctionDef | ast.ClassDef):
                found = node.name
            elif (
                isinstance(node, ast.Constant)
                and isinstance(node.value, str)
                and id(node) not in docstrings
            ):
                found = node.value
            if any(name in found.lower() for name in SCORING_NAMES):
                offenders.append(f"{path.name}:{getattr(node, 'lineno', 0)}: {found!r}")

    assert offenders == [], (
        "the gazetteer is deterministic longest-match and nothing else (NL-17) — "
        "the scoring line stays world-side, in the glossary service and the "
        f"lex-matcher-core / fuzzy-common engines: {offenders}"
    )


@pytest.mark.parametrize("module", sorted(_module_names()))
def test_importing_a_module_pulls_in_no_engine(module):
    """Runtime guard, one fresh interpreter per module.

    A subprocess is the only honest way to ask this: by the time pytest is
    running, its own imports have already populated `sys.modules`.
    """
    code = (
        f"import importlib, sys; importlib.import_module({module!r}); "
        f"banned = [m for m in {BANNED_MODULES!r} if m in sys.modules]; "
        "print(','.join(banned))"
    )
    out = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    )
    assert out.stdout.strip() == "", f"{module} pulled in {out.stdout.strip()}"
