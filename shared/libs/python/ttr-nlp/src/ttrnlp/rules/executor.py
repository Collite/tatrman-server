# SPDX-License-Identifier: Apache-2.0
"""The control styles — JAPE-exact (NL-13).

This is the core the whole effort turns on, and the reason it exists is one
sentence in JAPE's semantics that PAMPAC does not implement:

    Under **appelt**, exactly one rule fires per region, chosen by
    **longest match -> highest priority -> earliest rule in the file**.

PAMPAC's ``select`` is priority-only, and its ``"highest"`` mode is stricter
than that: it picks ``hp_rule_idx``, the first rule holding the *global* maximum
priority, computed once when the ``Pampac`` object is built. So a
lower-priority rule that matches more text can never win, and at a position
where the globally-highest rule does not match, nothing fires at all. Under real
appelt the longer match wins regardless of priority, which is what makes
priorities usable as a tie-break rather than as an override.

So the loop below is modelled on ``Pampac._run4span`` but collects candidates
from **every** rule at each region and applies the tie-break itself. The
compiler's ``matchtype="all"`` is what makes that possible: without full
enumeration the longest alternative is never offered.

The executor stays pure — it reads a document and returns what fired. Writing
annotations is ``actions.py``'s job, and keeping the two apart is what lets the
conformance suite assert *which rule won* separately from *what it produced*.
"""

from __future__ import annotations

from dataclasses import dataclass

from gatenlp.pam.pampac.data import Context, Location, Result

from ttrnlp.rules.compiler import CompiledRule

APPELT = "appelt"
BRILL = "brill"
ALL = "all"
FIRST = "first"
ONCE = "once"

CONTROL_STYLES = (APPELT, BRILL, ALL, FIRST, ONCE)


@dataclass(frozen=True)
class Firing:
    """One rule firing: which rule, over what span, with which bindings."""

    rule: CompiledRule
    start: int
    end: int
    result: Result

    @property
    def span(self) -> tuple[int, int]:
        return (self.start, self.end)


def _results(rule: CompiledRule, location: Location, context: Context) -> list[Result]:
    parsed = rule.parser.parse(location, context)
    return list(parsed) if parsed.issuccess() else []


def _longest(results: list[Result]) -> Result:
    return max(results, key=lambda r: r.span.end)


def _firing(rule: CompiledRule, result: Result) -> Firing:
    return Firing(
        rule=rule, start=result.span.start, end=result.span.end, result=result
    )


def _advance_to(context: Context, location: Location, offset: int) -> Location:
    """Move to a text offset and resynchronise the annotation index.

    Deliberately not ``inc_location(to_offset=…)``: that asserts the offset is
    inside the span, which the end of a match that reaches the end of the text
    is not.
    """
    moved = Location(offset, location.ann_location)
    return context.update_location_byoffset(moved)


def run_rules(
    doc,
    annotations,
    rules: list[CompiledRule],
    control: str,
) -> list[Firing]:
    """Match ``rules`` over ``annotations`` under one control style.

    Args:
        doc: The document (for text; not written to).
        annotations: The annotations the rules may see — already filtered to the
            phase's ``input:`` types by ``pipeline.py``. That filtering is the
            gap-skipping device: PAMPAC's ``Ann`` matches the *next* annotation
            in this list and does not skip over non-matching ones, so a type
            left out of ``input:`` is genuinely invisible rather than merely
            unmatched.
        rules: Compiled rules, in file order.
        control: One of ``CONTROL_STYLES``.

    Returns:
        The firings, in the order they fired.
    """
    if control not in CONTROL_STYLES:
        raise ValueError(
            f"unknown control style {control!r}; expected one of {CONTROL_STYLES}"
        )
    if not rules:
        return []

    context = Context(doc=doc, anns=annotations)
    location = Location(context.start, 0)
    firings: list[Firing] = []

    while True:
        fired_here, next_location = _step(context, location, rules, control)
        firings.extend(fired_here)

        if fired_here and control == ONCE:
            break

        if next_location is None:
            next_location = context.inc_location(location, by_offset=1)
        elif (
            next_location.text_location <= location.text_location
            and next_location.ann_location <= location.ann_location
        ):
            # A rule can match zero-width (an LHS of nothing but lookaheads).
            # Resuming where we already are would spin forever, so treat a
            # non-advancing match as "matched here, now move on".
            next_location = context.inc_location(location, by_offset=1)

        location = next_location
        if context.at_endofanns(location) or context.at_endoftext(location):
            break

    return firings


def _step(
    context: Context, location: Location, rules: list[CompiledRule], control: str
) -> tuple[list[Firing], Location | None]:
    """Try every rule at one region. Returns (firings, where to resume)."""
    if control == FIRST:
        return _step_first(context, location, rules)

    matches: list[tuple[CompiledRule, list[Result]]] = []
    for rule in rules:
        results = _results(rule, location, context)
        if results:
            matches.append((rule, results))

    if not matches:
        return [], None

    if control in (APPELT, ONCE):
        return _step_appelt(context, matches)
    if control == BRILL:
        return _step_brill(context, matches)
    return _step_all(context, matches)


def _step_appelt(
    context: Context, matches: list[tuple[CompiledRule, list[Result]]]
) -> tuple[list[Firing], Location]:
    """One rule fires: longest match -> highest priority -> earliest in file.

    `file_index` is negated so that `max` prefers the SMALLER index, which is
    the "earliest rule wins" half of JAPE's rule and the half most easily got
    backwards.
    """
    best_rule, best_result = max(
        ((rule, result) for rule, results in matches for result in results),
        key=lambda pair: (pair[1].span.end, pair[0].priority, -pair[0].file_index),
    )
    resume = _advance_to(context, best_result.location, best_result.span.end)
    return [_firing(best_rule, best_result)], resume


def _step_brill(
    context: Context, matches: list[tuple[CompiledRule, list[Result]]]
) -> tuple[list[Firing], Location]:
    """Every matching rule fires; resume after the longest of them."""
    firings = [_firing(rule, _longest(results)) for rule, results in matches]
    furthest = max(firing.end for firing in firings)
    resume = _advance_to(context, Location(furthest, 0), furthest)
    return firings, resume


def _step_all(
    context: Context, matches: list[tuple[CompiledRule, list[Result]]]
) -> tuple[list[Firing], Location]:
    """Every matching rule fires; resume just after the earliest match START.

    "Resume at the next offset" cannot mean "the next character": the current
    location sits at or before the start of what just matched, so a +1 step
    lands inside the same annotation and every rule fires on it again, forever
    producing duplicates. Advancing past the earliest match's *start* is what
    makes `all` differ from `brill` — overlapping matches from later positions
    are still found — without re-reporting the ones just found.
    """
    firings = [_firing(rule, _longest(results)) for rule, results in matches]
    earliest = min(firing.start for firing in firings)
    return firings, _advance_to(context, Location(earliest + 1, 0), earliest + 1)


def _step_first(
    context: Context, location: Location, rules: list[CompiledRule]
) -> tuple[list[Firing], Location | None]:
    """The first rule in file order that accepts, with no longest extension.

    "No longest extension" is taken literally: the SHORTEST way the rule can
    match. Not ``results[0]`` — PAMPAC's enumeration order is an undocumented
    property of `N`'s depth-first walk (it happens to yield the longest first),
    and a control style is a contract, not a side effect of a traversal that a
    gatenlp release could reorder.
    """
    for rule in rules:
        results = _results(rule, location, context)
        if results:
            result = min(results, key=lambda r: r.span.end)
            resume = _advance_to(context, result.location, result.span.end)
            return [_firing(rule, result)], resume
    return [], None


__all__ = [
    "ALL",
    "APPELT",
    "BRILL",
    "CONTROL_STYLES",
    "FIRST",
    "ONCE",
    "Firing",
    "run_rules",
]
