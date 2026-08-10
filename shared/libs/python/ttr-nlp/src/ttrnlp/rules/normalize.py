# SPDX-License-Identifier: Apache-2.0
"""Sugar to canonical form.

The DSL offers several spellings of the same thing so that packs read well. The
compiler should not have to know about any of them: it gets one canonical shape
and stays small. Everything sweet is dissolved here, once.

Three rewrites:

``repeat`` sugar
    ``"*"`` -> ``{min: 0, max: None}``, ``"+"`` -> ``{min: 1, max: None}``,
    ``"?"`` -> ``{min: 0, max: 1}``. After this, ``repeat`` is always a
    ``RepeatModel`` or absent.

token shorthands
    ``text: od`` -> ``{ann: Token, features: {text: od}}`` and likewise for
    ``lemma:``. After this, every matching step names its type.

single-step ``seq`` collapse
    ``group: {seq: [oneStep]}`` is just ``oneStep``. A group of one exists only
    because it was convenient to generate, and it costs a parser node at match
    time for nothing.

The collapse is conditional, which is the one subtle part. A group carries its
own ``bind`` and ``repeat``, and so does the step inside it. Where only one of
the two sets a modifier, the modifiers merge and the group disappears. Where
both do, they mean different things — ``{seq: [{ann: A, repeat: "+"}],
repeat: "?"}`` is "optionally, one or more As", which is not the same as either
modifier alone — so the group stays. Collapsing there would silently change what
the pack matches, and a normalisation pass that changes meaning is worse than no
normalisation pass.
"""

from __future__ import annotations

from ttrnlp.rules.dsl import (
    ActionModel,
    GroupModel,
    PackModel,
    PhaseModel,
    RepeatModel,
    RuleModel,
    StepModel,
)

#: Sugar spelling -> canonical interval.
REPEAT_SUGAR: dict[str, tuple[int, int | None]] = {
    "*": (0, None),
    "+": (1, None),
    "?": (0, 1),
}

TOKEN_TYPE = "Token"


def normalize_pack(pack: PackModel) -> PackModel:
    """Return a canonical copy of ``pack``. The input is not mutated."""
    return PackModel(
        pack=pack.pack,
        version=pack.version,
        phases=[_phase(phase) for phase in pack.phases],
    )


def _phase(phase: PhaseModel) -> PhaseModel:
    return PhaseModel(
        phase=phase.phase,
        input=list(phase.input),
        control=phase.control,
        rules=[_rule(rule) for rule in phase.rules],
    )


def _rule(rule: RuleModel) -> RuleModel:
    return RuleModel(
        rule=rule.rule,
        priority=rule.priority,
        lhs=[_step(step) for step in rule.lhs],
        rhs=[_action(action) for action in rule.rhs],
    )


def _action(action: ActionModel) -> ActionModel:
    # Actions carry no sugar today; copied so the result shares no structure
    # with the input.
    return action.model_copy(deep=True)


def _repeat(repeat: RepeatModel | str | None) -> RepeatModel | None:
    if repeat is None:
        return None
    if isinstance(repeat, RepeatModel):
        return repeat.model_copy()
    minimum, maximum = REPEAT_SUGAR[repeat]
    return RepeatModel(min=minimum, max=maximum)


def _step(step: StepModel) -> StepModel:
    normalized = _normalize_form(step)
    return _collapse_single_seq(normalized)


def _normalize_form(step: StepModel) -> StepModel:
    repeat = _repeat(step.repeat)

    # token shorthands -> an explicit Token step
    if step.text is not None or step.lemma is not None:
        key = "text" if step.text is not None else "lemma"
        return StepModel(
            ann=TOKEN_TYPE,
            features={key: step.text if key == "text" else step.lemma},
            repeat=repeat,
            bind=step.bind,
        )

    return StepModel(
        ann=step.ann,
        features=dict(step.features) if step.features is not None else None,
        contains=_step(step.contains) if step.contains is not None else None,
        within=_step(step.within) if step.within is not None else None,
        all=[_step(member) for member in step.all] if step.all is not None else None,
        group=_group(step.group) if step.group is not None else None,
        after=_step(step.after) if step.after is not None else None,
        notafter=_step(step.notafter) if step.notafter is not None else None,
        **{"not": _step(step.not_) if step.not_ is not None else None},
        repeat=repeat,
        bind=step.bind,
    )


def _group(group: GroupModel) -> GroupModel:
    if group.or_ is not None:
        return GroupModel.model_validate(
            {"or": [[_step(s) for s in branch] for branch in group.or_]}
        )
    return GroupModel(seq=[_step(s) for s in group.seq or []])


def _collapse_single_seq(step: StepModel) -> StepModel:
    """`group: {seq: [one]}` -> `one`, when the modifiers can merge."""
    group = step.group
    if group is None or group.seq is None or len(group.seq) != 1:
        return step

    inner = group.seq[0]
    if step.bind is not None and inner.bind is not None:
        return step
    if step.repeat is not None and inner.repeat is not None:
        return step

    merged = inner.model_copy(
        update={
            "bind": inner.bind if inner.bind is not None else step.bind,
            "repeat": inner.repeat if inner.repeat is not None else step.repeat,
        }
    )
    # The inner step may itself have been a one-step seq.
    return _collapse_single_seq(merged)


__all__ = ["REPEAT_SUGAR", "normalize_pack"]
