# SPDX-License-Identifier: Apache-2.0
"""THE validation path (NL-15) — and the one check the service never runs.

``validate_sources`` is ``load_sources`` with the raising taken out. It is not a
reimplementation and must never become one: it calls the same
``loader.gather_sources`` and the same ``loader.check_pipelines``, so a pack that
validates on a laptop validates identically at boot and at ``ReloadPacks``. The
whole promise of ``ttr-nlp validate`` rests on that, and a pack author who cannot
rely on it has no way to tell which of the two runs is the wrong one. There is a
test asserting the two report byte-identical diagnostics on the same fixtures.

**The exception is ``--model`` (NLS-PACK-005).** Cross-checking a
``QueryPattern``'s query id and parameter names against a TTR-M model is a
CLI-lane concern only, because the *service never sees model files* (contracts
§5): it is handed rule packs and answers questions about text, and shipping it a
model directory would make it care about a schema that is not its business. The
check therefore lives here rather than in the loader, and ``load_sources`` has no
``model`` parameter at all — not as an oversight, as the boundary.

**The model reader is deliberately tolerant.** It looks for query ids and
parameter names and ignores everything else, accepting the shapes a model file
plausibly uses for them. The TTR-M schema belongs to ``tatrman``; a strict reader
here would couple a published wheel's release cycle to a modeling language it
does not own, and the failure mode of guessing wrong is a false NLS-PACK-005 on a
pack that is fine — worse than the missed check, because it blocks a push.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any

import yaml

from ttrnlp.packs.diag import NLS_PACK_005, Diagnostic, error
from ttrnlp.packs.loader import Gathered, check_pipelines, gather_sources
from ttrnlp.rules.compiler import CompiledPack

#: The annotation type contracts §5 gives a query contract to. Other `add:` types
#: are the pack author's own vocabulary and are not model-checked.
QUERY_PATTERN_TYPE = "QueryPattern"

#: The feature carrying the opaque query id (contracts §5).
QUERY_FEATURE = "query"

MODEL_SUFFIXES = (".yaml", ".yml")


def validate_sources(
    sources: Iterable[str],
    *,
    model: str | Path | None = None,
    pipelines: Mapping[str, Any] | None = None,
) -> list[Diagnostic]:
    """Validate sources and return every diagnostic, raising nothing.

    Args:
        sources: Directories, single files, or ``http(s)`` URLs — exactly what
            ``load_sources`` takes.
        model: A directory of TTR-M model files. Enables the contracts §5
            cross-checks (``NLS-PACK-005``). CLI lane only.
        pipelines: A pipeline table to check references against (§7).

    Returns:
        Every diagnostic found, in source order. Empty means the sources would
        load.
    """
    gathered = gather_sources(sources)
    diagnostics = list(gathered.diagnostics)
    diagnostics.extend(check_pipelines(gathered, pipelines))
    if model is not None:
        diagnostics.extend(check_against_model(gathered, model))
    return diagnostics


# ── the model lane (NLS-PACK-005) ────────────────────────────────────────────


def read_model_queries(model_dir: str | Path) -> tuple[dict[str, set[str]], list[str]]:
    """Query ids -> declared parameter names, read tolerantly.

    Returns the queries and a list of notes about files that could not be read.
    An unreadable model file is reported rather than raised: the point of the
    ``--model`` lane is to catch pack mistakes, and a YAML file in a model
    directory that this reader does not understand is not one of them.
    """
    root = Path(model_dir)
    queries: dict[str, set[str]] = {}
    notes: list[str] = []

    if not root.exists():
        return queries, [f"model directory does not exist: {model_dir}"]

    paths = [root] if root.is_file() else sorted(root.rglob("*"))
    for path in paths:
        if not path.is_file() or path.suffix.lower() not in MODEL_SUFFIXES:
            continue
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, yaml.YAMLError) as exc:
            notes.append(f"could not read {path}: {exc}")
            continue
        if isinstance(document, Mapping):
            _collect_queries(document.get("queries"), queries)

    return queries, notes


def _collect_queries(node: Any, into: dict[str, set[str]]) -> None:
    """Accept both shapes a `queries:` block plausibly takes."""
    if isinstance(node, Mapping):
        # queries: {faktury_zakaznika: {parameters: [...]}}
        for query_id, spec in node.items():
            if isinstance(query_id, str):
                into[query_id] = _parameter_names(spec)
    elif isinstance(node, Sequence) and not isinstance(node, str):
        # queries: [{id: faktury_zakaznika, parameters: [...]}]
        for item in node:
            if not isinstance(item, Mapping):
                continue
            for key in ("id", "query", "name"):
                query_id = item.get(key)
                if isinstance(query_id, str):
                    into[query_id] = _parameter_names(item)
                    break


def _parameter_names(spec: Any) -> set[str]:
    """Parameter names from any of the three shapes they are written in."""
    if not isinstance(spec, Mapping):
        return set()
    node = spec.get("parameters")
    names: set[str] = set()
    if isinstance(node, Mapping):
        # parameters: {nazev_zakaznika: {type: string}}
        names.update(k for k in node if isinstance(k, str))
    elif isinstance(node, Sequence) and not isinstance(node, str):
        for item in node:
            if isinstance(item, str):
                names.add(item)  # parameters: [nazev_zakaznika]
            elif isinstance(item, Mapping):
                name = item.get("name") or item.get("id")
                if isinstance(name, str):
                    names.add(name)  # parameters: [{name: nazev_zakaznika}]
    return names


def check_against_model(
    gathered: Gathered, model_dir: str | Path
) -> list[Diagnostic]:
    """Every ``QueryPattern`` a pack emits must name a query the model declares.

    And every other feature on that annotation must be one of the query's
    declared parameters, because contracts §5 says a QueryPattern *is* the query
    id plus one feature per bound parameter. A pack that emits
    ``nazev_zakaznik`` where the model says ``nazev_zakaznika`` produces a
    QueryPattern the typed-parameter rail reads and finds nothing in — the
    query runs, with the parameter empty. That is the failure this catches, and
    it is precisely the kind that survives every test that does not have the
    model in front of it.
    """
    queries, notes = read_model_queries(model_dir)
    diagnostics = [
        error(NLS_PACK_005, note, source=str(model_dir)) for note in notes
    ]

    if not queries:
        diagnostics.append(
            error(
                NLS_PACK_005,
                f"no queries found under {model_dir} — nothing to cross-check "
                "against, so every `query:` id would be reported as unknown",
                source=str(model_dir),
            )
        )
        return diagnostics

    for compiled in gathered.packs.values():
        diagnostics.extend(_check_pack_against(compiled, queries))
    return diagnostics


def _check_pack_against(
    compiled: CompiledPack, queries: Mapping[str, set[str]]
) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []

    for phase in compiled.phases:
        for rule in phase.rules:
            where = f"$.phases[{phase.phase}].rules[{rule.rule}]"
            for action in rule.actions:
                add = action.add
                if add is None or add.type != QUERY_PATTERN_TYPE:
                    continue
                features = add.features or {}
                query_id = features.get(QUERY_FEATURE)
                if not isinstance(query_id, str):
                    # A getter, a number, or absent. contracts §5 makes the id a
                    # literal string; anything else is a pack the model cannot be
                    # asked about, and `checks.py` has already had its say about
                    # the shape.
                    continue
                if query_id not in queries:
                    diagnostics.append(
                        error(
                            NLS_PACK_005,
                            f"{where}: emits `query: {query_id}`, which the model "
                            f"does not declare (it has: "
                            f"{', '.join(sorted(queries))})",
                            pack=compiled.pack,
                        )
                    )
                    continue

                declared = queries[query_id]
                for feature in sorted(features):
                    if feature == QUERY_FEATURE or feature in declared:
                        continue
                    known = ", ".join(sorted(declared)) or "none"
                    diagnostics.append(
                        error(
                            NLS_PACK_005,
                            f"{where}: sets feature `{feature}` on the "
                            f"QueryPattern for `{query_id}`, which is not one of "
                            f"its declared parameters ({known}) — the "
                            "typed-parameter rail reads parameters by name, so "
                            "this one would arrive empty",
                            pack=compiled.pack,
                        )
                    )

    return diagnostics


__all__ = [
    "QUERY_FEATURE",
    "QUERY_PATTERN_TYPE",
    "check_against_model",
    "read_model_queries",
    "validate_sources",
]
