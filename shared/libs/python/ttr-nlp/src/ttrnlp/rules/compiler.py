# SPDX-License-Identifier: Apache-2.0
"""Normalised pack -> PAMPAC parser trees.

The compiler is where the DSL stops being data and becomes something that can
match. It runs on a **normalised** pack (``normalize.py`` has already dissolved
the sugar), so every step names its type and every ``repeat`` is an interval.

Two things it does that are worth knowing about.

**Everything compiles with ``matchtype="all"`` / ``select="all"``.** PAMPAC
defaults to returning the first way a parser could match. The appelt executor
needs the *longest*, so it needs to see every alternative and choose. Paying
for full enumeration here is what makes JAPE-exact tie-breaking possible at all.

**Compile-time errors are ``NLS-PACK-002``**, alongside ``checks.py``'s: they
are the same class of problem (the pack is well-formed but cannot work) and
they reach the author through the same CLI.

Two inherited boundaries, both pinned in the conformance matrix and both worth
knowing before writing a pack:

*``repeat`` is greedy and does not backtrack.* PAMPAC's ``N`` yields a result
only on reaching ``max`` or on the inner parser failing, never an intermediate
count. So ``{ann: T, repeat: "*"}, {ann: T}`` matches **nothing** — the
repetition takes every ``T`` and leaves the anchor none, and there is no
backtracking to give one back.

*``input:`` should list one layer.* ``Ann`` matches the NEXT annotation in the
visible list and never skips a non-matching one, so annotations of other types
sit between the ones a rule wants. Worse, two annotations at the same start
offset are ordered by insertion: a ``Lookup`` laid over a ``Token`` comes after
it, so with both types in ``input:`` that Lookup is never "next" and cannot be
matched at all. Lift token-level signals into the matching layer with an
earlier phase instead of widening ``input:``.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from gatenlp.pam.pampac import Ann, N, Or, Seq
from gatenlp.pam.pampac.pampac_parsers import PampacParser

from ttrnlp.packs.diag import NLS_PACK_002, DiagnosticCollector
from ttrnlp.rules.dsl import (
    ActionModel,
    GetterModel,
    InConstraint,
    PackModel,
    PhaseModel,
    RangeConstraint,
    RegexConstraint,
    RuleModel,
    StepModel,
)
from ttrnlp.rules.normalize import normalize_pack
from ttrnlp.rules.parsers import Conjunction, ZeroWidth

#: `N` needs a concrete `max`. Repetition terminates when the inner parser stops
#: matching, not when this bound is reached, so the bound only has to exceed any
#: possible number of annotations — it is not a silent cap on matching.
UNBOUNDED_REPEAT = 2**31 - 1


# ── compiled shapes ──────────────────────────────────────────────────────────


@dataclass(frozen=True)
class CompiledRule:
    """One rule, ready to match.

    ``file_index`` is the rule's position in its phase, and it is load-bearing:
    it is appelt's last tie-break, so "earlier in the file wins" is decided here
    rather than by whatever order a dict happens to iterate in.
    """

    rule: str
    priority: int
    file_index: int
    parser: PampacParser
    actions: list[ActionModel]
    #: bind name -> True when that binding is a single annotation (not a group
    #: or a repetition). `update:` and feature getters need a real annotation.
    annotation_binds: dict[str, bool] = field(default_factory=dict)


@dataclass(frozen=True)
class CompiledPhase:
    phase: str
    input: tuple[str, ...]
    control: str
    rules: list[CompiledRule]


@dataclass(frozen=True)
class CompiledPack:
    pack: str
    phases: list[CompiledPhase]

    def phase(self, name: str) -> CompiledPhase | None:
        return next((p for p in self.phases if p.phase == name), None)


# ── feature constraints ──────────────────────────────────────────────────────


def _constraint_matcher(value: Any) -> Any:
    """Turn one DSL constraint into something ``FeatureMatcher`` understands.

    It accepts a literal, a compiled regex, or a callable — so `in:` and the
    numeric comparisons become small closures.
    """
    if isinstance(value, RegexConstraint):
        return re.compile(value.regex)
    if isinstance(value, InConstraint):
        allowed = set(value.in_)
        return lambda actual: actual in allowed
    if isinstance(value, RangeConstraint):
        bounds = value
        return lambda actual: _in_range(actual, bounds)
    return value


def _in_range(actual: Any, bounds: RangeConstraint) -> bool:
    if not isinstance(actual, (int, float)) or isinstance(actual, bool):
        return False
    if bounds.gt is not None and not actual > bounds.gt:
        return False
    if bounds.gte is not None and not actual >= bounds.gte:
        return False
    if bounds.lt is not None and not actual < bounds.lt:
        return False
    if bounds.lte is not None and not actual <= bounds.lte:
        return False
    return True


def _features(step: StepModel) -> dict[str, Any] | None:
    if not step.features:
        return None
    return {name: _constraint_matcher(value) for name, value in step.features.items()}


# ── steps ────────────────────────────────────────────────────────────────────


def _simple_ann(step: StepModel) -> bool:
    """True for a bare annotation constraint — the only thing gatenlp's
    containment filters can express, since they take matcher arguments rather
    than a parser."""
    return (
        step.ann is not None
        and step.repeat is None
        and step.all is None
        and step.group is None
        and step.contains is None
        and step.within is None
    )


def _compile_step(
    step: StepModel, collector: DiagnosticCollector, where: str
) -> tuple[PampacParser, bool]:
    """Compile one step. Returns (parser, binds_a_single_annotation)."""
    core, is_annotation = _compile_form(step, collector, where)

    if step.repeat is not None:
        maximum = step.repeat.max if step.repeat.max is not None else UNBOUNDED_REPEAT
        core = N(
            core,
            min=step.repeat.min,
            max=maximum,
            matchtype="all",
            select="all",
            name=step.bind,
        )
        # A repetition binds a span, never one annotation.
        return core, False

    nameable = (Ann, ZeroWidth, Conjunction, Seq)
    if step.bind is not None and not isinstance(core, nameable):
        # `Or` takes no name; wrap it so the binding still records a span.
        core = Seq(core, name=step.bind, matchtype="all", select="all")
    elif step.bind is not None:
        core.name = step.bind

    return core, is_annotation


def _compile_form(
    step: StepModel, collector: DiagnosticCollector, where: str
) -> tuple[PampacParser, bool]:
    if step.ann is not None:
        parser: PampacParser = Ann(type=step.ann, features=_features(step))
        parser = _apply_containment(parser, step, collector, where)
        return parser, True

    if step.all is not None:
        members = [_compile_step(m, collector, f"{where}.all")[0] for m in step.all]
        if len(members) == 1:
            return members[0], False
        return Conjunction(*members), False

    if step.not_ is not None:
        inner, _ = _compile_step(step.not_, collector, f"{where}.not")
        return ZeroWidth(inner, negate=True), False

    if step.after is not None:
        inner, _ = _compile_step(step.after, collector, f"{where}.after")
        return ZeroWidth(inner), False

    if step.notafter is not None:
        inner, _ = _compile_step(step.notafter, collector, f"{where}.notafter")
        return ZeroWidth(inner, negate=True), False

    if step.group is not None:
        if step.group.or_ is not None:
            branches = [
                _compile_sequence(branch, collector, f"{where}.or")
                for branch in step.group.or_
            ]
            return Or(*branches, matchtype="all"), False
        return _compile_sequence(step.group.seq or [], collector, f"{where}.seq"), False

    raise AssertionError(f"unreachable: step with no form key at {where}")


def _apply_containment(
    parser: PampacParser, step: StepModel, collector: DiagnosticCollector, where: str
) -> PampacParser:
    """Wrap an annotation parser in `contains:` / `within:` filters.

    Note the deliberate inversion: gatenlp's ``parser.covering(...)`` means "the
    match covers a matching annotation", which is our ``contains:``; and
    ``parser.within(...)`` means "the match sits inside one", which is our
    ``within:``. Getting these the wrong way round produces a rule that matches
    nothing, silently, so there is a test for each direction.
    """
    for key, nested in (("contains", step.contains), ("within", step.within)):
        if nested is None:
            continue
        if not _simple_ann(nested):
            collector.error(
                NLS_PACK_002,
                f"{where}: `{key}:` takes a plain annotation constraint "
                "(`ann:` with optional `features:`) — a group, repetition or "
                "nested containment cannot be expressed as a containment filter",
            )
            continue
        arguments = {"type": nested.ann, "features": _features(nested)}
        parser = (
            parser.covering(**arguments)
            if key == "contains"
            else parser.within(**arguments)
        )
    return parser


def _compile_sequence(
    steps: list[StepModel], collector: DiagnosticCollector, where: str
) -> PampacParser:
    parsers = [_compile_step(step, collector, where)[0] for step in steps]
    if len(parsers) == 1:
        return parsers[0]
    return Seq(*parsers, matchtype="all", select="all")


# ── rules, phases, packs ─────────────────────────────────────────────────────


def _collect_binds(steps: list[StepModel], into: dict[str, bool]) -> None:
    for step in steps:
        if step.bind is not None:
            into[step.bind] = step.ann is not None and step.repeat is None
        _collect_binds(step.substeps(), into)


def _check_action_bindings(
    rule: RuleModel, binds: dict[str, bool], collector: DiagnosticCollector, where: str
) -> None:
    """`update:` and feature getters need an ANNOTATION, not just a span.

    A binding on a group or a repetition records where the match was, not which
    annotation it was — there may have been several, or none. Reading a feature
    off that is meaningless, and it would surface at match time as a missing
    feature rather than as the authoring mistake it is.
    """

    def demand_annotation(name: str, what: str) -> None:
        if binds.get(name) is False:
            collector.error(
                NLS_PACK_002,
                f"{where}: {what} reads from `{name}`, which is bound to a group "
                "or a repetition rather than to a single annotation — bind the "
                "`ann:` step itself, or use `@string` to take the matched text",
            )

    for action in rule.rhs:
        if action.add is not None:
            for feature, value in (action.add.features or {}).items():
                if isinstance(value, GetterModel) and not value.is_span_getter:
                    demand_annotation(value.from_, f"`add.features.{feature}`")
        if action.update is not None:
            demand_annotation(action.update.on, "`update.on`")
            for feature, value in (action.update.features or {}).items():
                if isinstance(value, GetterModel) and not value.is_span_getter:
                    demand_annotation(value.from_, f"`update.features.{feature}`")


def compile_pack(
    pack: PackModel, *, source: str = "", normalize: bool = True
) -> CompiledPack:
    """Compile a pack into PAMPAC parser trees.

    Args:
        pack: A parsed pack. Normalised here unless ``normalize=False``, which
            is only for callers that already normalised it.
        source: Where it came from, for diagnostics.

    Raises:
        PackError: With ``NLS-PACK-002`` diagnostics for anything that parses
            but cannot be compiled.
    """
    if normalize:
        pack = normalize_pack(pack)
    collector = DiagnosticCollector(source=source, pack=pack.pack)

    phases = []
    for phase in pack.phases:
        phases.append(_compile_phase(phase, collector))

    collector.raise_if_any()
    return CompiledPack(pack=pack.pack, phases=phases)


def _compile_phase(phase: PhaseModel, collector: DiagnosticCollector) -> CompiledPhase:
    rules = []
    for index, rule in enumerate(phase.rules):
        where = f"$.phases[{phase.phase}].rules[{rule.rule}]"
        binds: dict[str, bool] = {}
        _collect_binds(rule.lhs, binds)
        _check_action_bindings(rule, binds, collector, where)
        rules.append(
            CompiledRule(
                rule=rule.rule,
                priority=rule.priority,
                file_index=index,
                parser=_compile_sequence(rule.lhs, collector, where),
                actions=list(rule.rhs),
                annotation_binds=binds,
            )
        )
    return CompiledPhase(
        phase=phase.phase,
        input=tuple(phase.input),
        control=phase.control,
        rules=rules,
    )


__all__ = [
    "UNBOUNDED_REPEAT",
    "CompiledPack",
    "CompiledPhase",
    "CompiledRule",
    "compile_pack",
]
