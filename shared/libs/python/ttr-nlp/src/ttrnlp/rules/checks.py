# SPDX-License-Identifier: Apache-2.0
"""Parse-time cross-checks — ``NLS-PACK-002``.

The schema can check a pack's *shape*. These are the checks about how its parts
refer to one another, which no schema can express:

* a step matches a type the phase's ``input:`` does not make visible
* the RHS references a name the LHS never bound
* one rule binds the same name twice

They share a failure mode, and it is the reason they are errors rather than
warnings: each produces a rule that **silently never fires**. Nothing crashes,
no annotation appears, and the pack looks correct. Catching these at load — in
the CLI, before a push — is the difference between a validation message and an
afternoon.

JAPE treats the ``Input:`` violation the same way, and for the same reason.
"""

from __future__ import annotations

from collections import Counter

from ttrnlp.packs.diag import NLS_PACK_002, DiagnosticCollector
from ttrnlp.rules.dsl import (
    GetterModel,
    PackModel,
    PhaseModel,
    RuleModel,
    StepModel,
)

#: The type the token shorthands (`text:` / `lemma:`) stand for.
TOKEN_TYPE = "Token"


def _walk(steps: list[StepModel]):
    """Yield every step in a LHS, depth-first, including nested ones."""
    for step in steps:
        yield step
        yield from _walk(step.substeps())


def _matched_type(step: StepModel) -> str | None:
    """The annotation type a step matches, or None if it matches no type itself.

    The token shorthands matter here: an author who wrote ``lemma: faktura``
    never typed "Token", so a phase missing Token from `input:` breaks a rule
    that mentions no type at all.
    """
    if step.ann is not None:
        return step.ann
    if step.text is not None or step.lemma is not None:
        return TOKEN_TYPE
    return None


def _bound_names(rule: RuleModel) -> set[str]:
    return {step.bind for step in _walk(rule.lhs) if step.bind is not None}


def _bind_counts(steps: list[StepModel]) -> Counter[str]:
    """How many times each name can be bound by a single match of `steps`.

    Alternation branches merge by **max**, not by sum: only one branch of an
    `or` can match, so the same name in two branches is the idiomatic way to say
    "whichever of these matched, call it this". Summing would ban that.
    """
    total: Counter[str] = Counter()
    for step in steps:
        if step.bind is not None:
            total[step.bind] += 1
        if step.group is not None and step.group.or_ is not None:
            branches: Counter[str] = Counter()
            for branch in step.group.or_:
                for name, count in _bind_counts(branch).items():
                    branches[name] = max(branches[name], count)
            total += branches
        else:
            total += _bind_counts(step.substeps())
    return total


def _duplicate_binds(rule: RuleModel) -> list[str]:
    return sorted(name for name, count in _bind_counts(rule.lhs).items() if count > 1)


def _referenced_names(rule: RuleModel) -> list[tuple[str, str]]:
    """(name, what-referenced-it) for every RHS reference to a binding."""
    references: list[tuple[str, str]] = []
    for action in rule.rhs:
        if action.add is not None:
            if action.add.span is not None:
                references.append((action.add.span, "add.span"))
            for feature, value in (action.add.features or {}).items():
                if isinstance(value, GetterModel):
                    references.append((value.from_, f"add.features.{feature}.from"))
        if action.update is not None:
            references.append((action.update.on, "update.on"))
            for feature, value in (action.update.features or {}).items():
                if isinstance(value, GetterModel):
                    references.append((value.from_, f"update.features.{feature}.from"))
    return references


def check_pack(pack: PackModel, *, source: str = "") -> PackModel:
    """Run every cross-check over a parsed pack.

    Args:
        pack: A pack that already parsed and schema-validated.
        source: Where it came from, for diagnostics.

    Returns:
        The same pack, so this composes as ``check_pack(load_pack(path))``.

    Raises:
        PackError: With every ``NLS-PACK-002`` diagnostic found.
    """
    collector = DiagnosticCollector(source=source, pack=pack.pack)

    for phase in pack.phases:
        visible = set(phase.input)
        for rule in phase.rules:
            _check_visibility(collector, phase, rule, visible)
            _check_bindings(collector, phase, rule)

    collector.raise_if_any()
    return pack


def _where(phase: PhaseModel, rule: RuleModel) -> str:
    return f"$.phases[{phase.phase}].rules[{rule.rule}]"


def _check_visibility(
    collector: DiagnosticCollector,
    phase: PhaseModel,
    rule: RuleModel,
    visible: set[str],
) -> None:
    reported: set[str] = set()
    for step in _walk(rule.lhs):
        anntype = _matched_type(step)
        if anntype is None or anntype in visible or anntype in reported:
            continue
        reported.add(anntype)
        via = ""
        if step.ann is None:
            shorthand = "text" if step.text is not None else "lemma"
            via = f" (via the `{shorthand}:` shorthand)"
        collector.error(
            NLS_PACK_002,
            f"{_where(phase, rule)}: matches `{anntype}`{via}, which phase "
            f"`{phase.phase}` does not list in `input:` "
            f"({', '.join(sorted(visible))}) — the type is invisible to this "
            "phase, so the rule could never fire",
        )


def _check_bindings(
    collector: DiagnosticCollector, phase: PhaseModel, rule: RuleModel
) -> None:
    for name in _duplicate_binds(rule):
        collector.error(
            NLS_PACK_002,
            f"{_where(phase, rule)}: duplicate `bind: {name}` — two steps in one "
            "rule claim the same name, so the RHS cannot say which it means",
        )

    bound = _bound_names(rule)
    for name, referenced_by in _referenced_names(rule):
        if name not in bound:
            known = ", ".join(sorted(bound)) if bound else "none"
            collector.error(
                NLS_PACK_002,
                f"{_where(phase, rule)}: `{referenced_by}` references `{name}`, "
                f"which this rule's LHS never binds (bound here: {known}) — "
                "bindings are rule-scoped",
            )


__all__ = ["check_pack"]
