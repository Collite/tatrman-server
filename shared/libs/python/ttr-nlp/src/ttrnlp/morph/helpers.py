# SPDX-License-Identifier: Apache-2.0
"""Feature matchers for rules that need to read morphology (contracts §5).

The DSL's ``features:`` constraints compile to whatever gatenlp's
`FeatureMatcher` understands, and that includes a **callable** — the compiler
already builds closures for ``in:`` and the numeric ranges. These are the same
thing for the three questions a rule asks a morph-annotated token:

    lemma_any("tržba", "obrat")   any candidate lemma is one of these
    upos_any("NOUN", "PROPN")     the part of speech is one of these
    feats_has("Case=Gen")         some reading carries this atom

Each of them accepts **either shape of the feature** — the single value an
engine wrote (``lemma``) or the list/record the morph annotator wrote
(``lemmas``, ``analyses``). A rule author should not have to know which stage
produced the document, and a rule that silently stops matching when the pipeline
changes is exactly the failure mode the whole one-substrate rule exists to
prevent.

Not expressible in YAML, by construction: a pack is data and a callable is not.
These are for rules built in Python — the service's own wiring, the eval
harness, and tests — while pack authors keep writing ``lemma: faktura``, which
the head-of-list feature still satisfies.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping

Matcher = Callable[[object], bool]


def _is_analyses(actual: object) -> bool:
    """True for the `analyses` feature — a list of analysis records."""
    return (
        isinstance(actual, (list, tuple))
        and bool(actual)
        and isinstance(actual[0], Mapping)
    )


def _values(actual: object) -> list[str]:
    """Flatten whatever a feature holds into candidate strings."""
    if actual is None:
        return []
    if isinstance(actual, str):
        return [actual]
    if isinstance(actual, Mapping):
        # An engine's `feats` dict: {"Case": "Gen"} reads as "Case=Gen".
        return [f"{key}={value}" for key, value in actual.items()]
    if isinstance(actual, (list, tuple, set, frozenset)):
        flat: list[str] = []
        for item in actual:
            flat.extend(_values(item))
        return flat
    return [str(actual)]


def lemma_any(*lemmas: str) -> Matcher:
    """Match when any candidate lemma is one of ``lemmas``.

    Reads `lemmas` (the ranked list) or `lemma` (a single value) equally, which
    is the point: *byt* must match a token whose head lemma is *být*, or the
    ranking — a best guess with nothing behind it — decides which rules fire.
    """
    wanted = frozenset(lemmas)

    def matcher(actual: object) -> bool:
        return any(value in wanted for value in _values(actual))

    return matcher


def upos_any(*upos: str) -> Matcher:
    """Match when any candidate part of speech is one of ``upos``."""
    wanted = frozenset(upos)

    def matcher(actual: object) -> bool:
        if _is_analyses(actual):
            return any(str(a.get("upos")) in wanted for a in actual)
        return any(value in wanted for value in _values(actual))

    return matcher


def feats_has(atom: str) -> Matcher:
    """Match when some reading carries ``atom`` (e.g. ``Case=Gen``).

    A reading is a ``|``-joined atom string, so this splits rather than
    substring-matches: ``Case=Gen`` must not be satisfied by ``Case=Gen2`` or by
    a lemma that happens to contain the text.
    """

    def matcher(actual: object) -> bool:
        for value in _values(actual):
            if atom in value.split("|"):
                return True
        return False

    def analyses_matcher(actual: object) -> bool:
        if _is_analyses(actual):
            return any(
                matcher(analysis.get("feats"))
                for analysis in actual
                if isinstance(analysis, Mapping)
            )
        return matcher(actual)

    return analyses_matcher


__all__ = ["Matcher", "feats_has", "lemma_any", "upos_any"]
