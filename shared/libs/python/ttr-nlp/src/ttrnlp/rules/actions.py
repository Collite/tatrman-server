# SPDX-License-Identifier: Apache-2.0
"""The RHS — what a firing writes.

Two actions, a closed set of getters, and no code (P-2). A pack can add an
annotation or amend one it matched; it cannot call anything. That is what lets a
rule pack be reviewed by reading it.

Written as a thin layer over the match bindings rather than by handing PAMPAC's
``AddAnn``/``UpdateAnnFeatures`` the work. Those carry their own conventions
about which result index and which name they apply to, and bending them to the
DSL's ``span:``/``set:``/getter semantics would mean fighting defaults that are
invisible from the pack. Reading the bindings and calling ``annset.add`` is
shorter and does exactly what the YAML says.
"""

from __future__ import annotations

from typing import Any

from gatenlp.pam.pampac.data import Result

from ttrnlp.rules.dsl import (
    GETTER_LENGTH,
    GETTER_STRING,
    ActionModel,
    GetterModel,
)
from ttrnlp.rules.executor import Firing


class ActionError(RuntimeError):
    """A firing could not be applied. Compile-time checks make this rare."""


def _binding_span(result: Result, name: str) -> tuple[int, int] | None:
    """The span a name is bound to.

    A name bound inside a repetition can match several times; the union of
    those spans is what "the text this binding covered" means, and it is what
    ``@string`` should return.
    """
    matches = result.matches4name(name)
    if not matches:
        return None
    starts = [m["span"].start for m in matches]
    ends = [m["span"].end for m in matches]
    return min(starts), max(ends)


def _binding_annotation(result: Result, name: str):
    for match in result.matches4name(name):
        ann = match.get("ann")
        if ann is not None:
            return ann
    return None


def _resolve(value: Any, firing: Firing, doc) -> Any:
    if not isinstance(value, GetterModel):
        return value

    name = value.from_
    if value.get == GETTER_STRING:
        span = _binding_span(firing.result, name)
        return doc.text[span[0] : span[1]] if span else None
    if value.get == GETTER_LENGTH:
        span = _binding_span(firing.result, name)
        return span[1] - span[0] if span else None

    ann = _binding_annotation(firing.result, name)
    if ann is None:
        # The compiler rejects a feature getter on a group or repetition
        # binding, so reaching here means the binding did not participate in
        # this particular match — an `or` branch that was not taken, say.
        return None
    return ann.features.get(value.get)


def _features(source: dict[str, Any] | None, firing: Firing, doc) -> dict[str, Any]:
    if not source:
        return {}
    return {name: _resolve(value, firing, doc) for name, value in source.items()}


def apply_firing(doc, firing: Firing, *, working_set: str = "") -> list:
    """Run one firing's actions. Returns the annotations added."""
    added = []
    for action in firing.rule.actions:
        if action.add is not None:
            added.append(_apply_add(doc, firing, action, working_set))
        elif action.update is not None:
            _apply_update(doc, firing, action)
    return [ann for ann in added if ann is not None]


def _apply_add(doc, firing: Firing, action: ActionModel, working_set: str):
    add = action.add
    if add.span is None:
        start, end = firing.start, firing.end
    else:
        span = _binding_span(firing.result, add.span)
        if span is None:
            # The named step did not participate in this match — an untaken `or`
            # branch, or a repetition that matched zero times. Adding an
            # annotation over the whole match instead would be a quiet lie about
            # where the value came from, so add nothing.
            return None
        start, end = span

    annset = doc.annset(add.set if add.set is not None else working_set)
    return annset.add(start, end, add.type, _features(add.features, firing, doc))


def _apply_update(doc, firing: Firing, action: ActionModel) -> None:
    update = action.update
    ann = _binding_annotation(firing.result, update.on)
    if ann is None:
        return
    ann.features.update(_features(update.features, firing, doc))


def apply_firings(doc, firings: list[Firing], *, working_set: str = "") -> list:
    added = []
    for firing in firings:
        added.extend(apply_firing(doc, firing, working_set=working_set))
    return added


__all__ = ["ActionError", "apply_firing", "apply_firings"]
