# SPDX-License-Identifier: Apache-2.0
"""The rule DSL — YAML in, validated ``PackModel`` out.

A rule pack is a declarative YAML document with JAPE's semantics and spaCy-ish
constraint keys (NL-1). It is deliberately *not* JAPE syntax: analysts who will
read and review these files should not have to learn a Java-era grammar, and the
suite should not have to ship a CPSL parser to serve them.

Nothing here executes. The RHS is a closed set of two actions over a closed set
of getters (P-2), so a pack is data all the way down — reviewable, diffable, and
incapable of doing anything a reader cannot see.

Load order, and why it is that order::

    YAML text
      -> yaml.safe_load          syntax
      -> jsonschema.validate     structure   -> NLS-PACK-001 with a JSON path
      -> pydantic parse          the rules JSON Schema cannot state -> NLS-PACK-001
      -> checks.py (separate)    cross-references                   -> NLS-PACK-002

The schema runs first because it produces by far the best messages: it knows the
path to the offending node and the set of keys that were legal there. Pydantic
would report the same typo as "no form key present", which is true and useless.

Vocabulary
==========

The LHS table below is the contract (contracts §3). The JAPE column is what each
form corresponds to for anyone arriving from GATE.

====================  =========================================  ===================
Form                  Keys                                       JAPE
====================  =========================================  ===================
annotation            ``ann: <Type>`` + optional ``features:``   ``{Type.k == v}``
token text            ``text: <s>``                              ``{Token.string==s}``
token lemma           ``lemma: <s>``                             ``{Token.lemma==s}``
conjunction           ``all: [Step…]``, members may ``not:``     ``{A, !B}``
containment           ``contains: Step`` on an ``ann`` step      ``contains``
containment           ``within: Step`` on an ``ann`` step        ``within``
alternation           ``group: {or: [[Step…], [Step…]]}``        ``( … | … )``
sequence group        ``group: {seq: [Step…]}``                  ``( … )``
lookahead             ``after: Step`` (zero-width)               consumed context
negative lookahead    ``notafter: Step`` (zero-width)            ``!``
====================  =========================================  ===================

Modifiers on any step or group: ``repeat: {min, max}`` (sugar ``*`` ``+`` ``?``)
and ``bind: <name>``, unique within a rule.

Feature constraint values: a literal (string/number/bool), ``{regex: …}``,
``{in: […]}``, or an object combining any of ``gt`` / ``gte`` / ``lt`` / ``lte``.

Actions (RHS), and there are only these two::

    - add:                            # add a new annotation
        type: QueryPattern
        span: <bind>                  # optional; default = the whole match
        set: <annotation-set>         # optional; default = the working set
        features:
          query: faktury_zakaznika                        # literal
          nazev_zakaznika: {from: <bind>, get: "@string"}  # or a getter

    - update:                         # amend a bound annotation's features
        on: <bind>
        features: {verified: company}

Getters: ``@string`` is the span's text, ``@length`` its character length, and
any other name reads that feature off the bound annotation.

Control styles (NL-13, JAPE-exact — see ``executor.py`` for the implementation):
``appelt`` fires exactly one rule per region, tie-broken **longest match →
highest priority → earliest rule in the file**, and resumes after the match;
``brill`` fires every accepting match and resumes at the maximum end; ``all``
fires every match and resumes at the next offset; ``first`` takes the first
acceptance without extending to the longest; ``once`` stops the phase after a
single firing.
"""

from __future__ import annotations

import json
import re
from functools import lru_cache
from importlib import resources
from pathlib import Path
from typing import Any

import jsonschema
import jsonschema.exceptions
import yaml
from jsonschema import Draft202012Validator
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from ttrnlp.packs.diag import NLS_PACK_001, Diagnostic, PackError, error

SCHEMA_RESOURCE = "schema/pack.schema.json"

#: The `get:` values that are not feature names.
GETTER_STRING = "@string"
GETTER_LENGTH = "@length"

#: Form keys — exactly one must be present on a step.
STEP_FORM_KEYS = ("ann", "text", "lemma", "all", "group", "after", "notafter", "not")


class PackLoader(yaml.SafeLoader):
    """A safe loader that resolves booleans the way YAML **1.2** does.

    PyYAML implements YAML 1.1, where ``on``, ``off``, ``yes`` and ``no`` are
    booleans. The DSL's update action is spelled ``on: <bind-name>`` (contracts
    §3), so under 1.1 that key parses as ``True`` and the action arrives with no
    ``on`` at all — reported as "'on' is a required property" while the author
    is staring straight at it.

    Quoting it in every pack would work and would be a trap: forget the quotes
    once and the error blames the wrong thing. YAML 1.2's core schema — the
    current spec, ten-plus years old — recognises only ``true``/``false``, which
    makes ``on:`` an ordinary key and needs nothing from the author.

    The trade-off, stated because packs are user-authored: ``yes``/``no``/
    ``on``/``off`` as *values* are strings here, not booleans. Write ``true`` or
    ``false`` for a boolean.
    """


PackLoader.yaml_implicit_resolvers = {
    first_char: [
        (tag, regexp) for tag, regexp in resolvers if tag != "tag:yaml.org,2002:bool"
    ]
    for first_char, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
PackLoader.add_implicit_resolver(
    "tag:yaml.org,2002:bool",
    re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"),
    list("tTfF"),
)


@lru_cache(maxsize=1)
def load_schema() -> dict[str, Any]:
    """The DSL JSON Schema, read from package data.

    Read from the *installed* package rather than a path relative to this file,
    so it resolves identically from a source tree, a wheel and a zipapp. The
    publish workflow verifies the file is actually in the wheel.
    """
    text = resources.files("ttrnlp.rules").joinpath(SCHEMA_RESOURCE).read_text(
        encoding="utf-8"
    )
    return json.loads(text)


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    schema = load_schema()
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


# ── models ───────────────────────────────────────────────────────────────────


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class RepeatModel(_Base):
    """A normalised repetition interval. ``max=None`` is unbounded."""

    min: int = 0
    max: int | None = None

    @model_validator(mode="after")
    def _min_le_max(self) -> RepeatModel:
        if self.max is not None and self.min > self.max:
            raise ValueError(
                f"repeat.min ({self.min}) is greater than repeat.max ({self.max}); "
                "the interval can never be satisfied, so the rule could never fire"
            )
        return self


class GetterModel(_Base):
    """``{from: <bind>, get: "@string" | "@length" | <feature>}``."""

    from_: str = Field(alias="from")
    get: str

    @property
    def is_span_getter(self) -> bool:
        return self.get in (GETTER_STRING, GETTER_LENGTH)


Literal_ = str | int | float | bool
FeatureValue = Literal_ | GetterModel


class RegexConstraint(_Base):
    regex: str

    @model_validator(mode="after")
    def _compiles(self) -> RegexConstraint:
        try:
            re.compile(self.regex)
        except re.error as exc:
            raise ValueError(f"regex {self.regex!r} does not compile: {exc}") from exc
        return self


class InConstraint(_Base):
    in_: list[Literal_] = Field(alias="in")


class RangeConstraint(_Base):
    gt: float | None = None
    gte: float | None = None
    lt: float | None = None
    lte: float | None = None


Constraint = RegexConstraint | InConstraint | RangeConstraint | Literal_


class GroupModel(_Base):
    """``or`` alternation or ``seq`` sequence — exactly one."""

    or_: list[list[StepModel]] | None = Field(default=None, alias="or")
    seq: list[StepModel] | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> GroupModel:
        present = [k for k, v in (("or", self.or_), ("seq", self.seq)) if v is not None]
        if len(present) != 1:
            raise ValueError(
                "a group needs exactly one of `or` / `seq`, "
                f"found {present or 'neither'}"
            )
        return self


class StepModel(_Base):
    """One matching step.

    Every form is a field here and exactly one may be set; see the module
    docstring's table. The flat shape mirrors the schema's, which keeps error
    paths identical on both sides of the validation boundary.
    """

    # forms
    ann: str | None = None
    text: str | None = None
    lemma: str | None = None
    all: list[StepModel] | None = None
    group: GroupModel | None = None
    after: StepModel | None = None
    notafter: StepModel | None = None
    not_: StepModel | None = Field(default=None, alias="not")
    # `ann`-only refinements
    features: dict[str, Constraint] | None = None
    contains: StepModel | None = None
    within: StepModel | None = None
    # modifiers
    repeat: RepeatModel | str | None = None
    bind: str | None = None

    @model_validator(mode="after")
    def _exactly_one_form(self) -> StepModel:
        present = [k for k in STEP_FORM_KEYS if getattr(self, _attr(k)) is not None]
        if len(present) != 1:
            raise ValueError(
                "a step needs exactly one form key "
                f"({' | '.join(STEP_FORM_KEYS)}), found "
                f"{present if present else 'none'}"
            )
        return self

    @property
    def form(self) -> str:
        return next(k for k in STEP_FORM_KEYS if getattr(self, _attr(k)) is not None)

    def substeps(self) -> list[StepModel]:
        """Every step directly nested under this one, in no particular order."""
        nested: list[StepModel] = []
        for attr in ("all", "after", "notafter", "not_", "contains", "within"):
            value = getattr(self, attr)
            if isinstance(value, StepModel):
                nested.append(value)
            elif isinstance(value, list):
                nested.extend(value)
        if self.group is not None:
            for branch in self.group.or_ or []:
                nested.extend(branch)
            nested.extend(self.group.seq or [])
        return nested


def _attr(form_key: str) -> str:
    """Field name for a form key (`not` and `all` collide with builtins/keywords)."""
    return "not_" if form_key == "not" else form_key


class AddActionModel(_Base):
    type: str
    span: str | None = None
    set: str | None = None
    features: dict[str, FeatureValue] | None = None


class UpdateActionModel(_Base):
    on: str
    features: dict[str, FeatureValue]


class ActionModel(_Base):
    """Exactly one of ``add`` / ``update``."""

    add: AddActionModel | None = None
    update: UpdateActionModel | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> ActionModel:
        present = [k for k, v in (("add", self.add), ("update", self.update)) if v]
        if len(present) != 1:
            raise ValueError(
                "an action needs exactly one of `add` / `update`, "
                f"found {present or 'neither'}"
            )
        return self


class RuleModel(_Base):
    rule: str
    priority: int = 0
    lhs: list[StepModel]
    rhs: list[ActionModel]


class PhaseModel(_Base):
    phase: str
    input: list[str]
    control: str
    rules: list[RuleModel]


class PackModel(_Base):
    pack: str
    version: int
    phases: list[PhaseModel]

    @model_validator(mode="after")
    def _not_only_inside_all(self) -> PackModel:
        """`not:` is a conjunction member, not a step in its own right.

        The schema can say "`not` is a known key"; it cannot say "only as a
        member of `all`", because that depends on the parent. A bare `not:` in
        an LHS sequence looks like it should mean "match anything but this",
        which is not what it does and not something the matcher can do — so it
        is rejected here, where the message can explain rather than just refuse.
        """
        for phase in self.phases:
            for rule in phase.rules:
                for index, step in enumerate(rule.lhs):
                    _reject_misplaced_not(
                        step, f"$.phases.{phase.phase}.rules.{rule.rule}.lhs[{index}]"
                    )
        return self


def _reject_misplaced_not(
    step: StepModel, path: str, *, inside_all: bool = False
) -> None:
    if step.not_ is not None and not inside_all:
        raise ValueError(
            f"{path}: `not:` is only legal as a member of `all:` — "
            "outside a conjunction there is no annotation for it to negate"
        )
    for index, member in enumerate(step.all or []):
        _reject_misplaced_not(member, f"{path}.all[{index}]", inside_all=True)
    for nested in step.substeps():
        if step.all and nested in step.all:
            continue
        _reject_misplaced_not(nested, path)


# Self-referential models: the annotations are strings under
# `from __future__ import annotations`, and GroupModel names StepModel before it
# exists. Resolve both once the module body is complete.
StepModel.model_rebuild()
GroupModel.model_rebuild()
RuleModel.model_rebuild()
PhaseModel.model_rebuild()
PackModel.model_rebuild()


# ── loading ──────────────────────────────────────────────────────────────────


def _unexpected_keys(err: jsonschema.ValidationError) -> list[str]:
    allowed = set(err.schema.get("properties", {}))
    if isinstance(err.instance, dict):
        return sorted(set(err.instance) - allowed)
    return []


def _path_of(err: jsonschema.ValidationError) -> str:
    path = err.json_path
    # An additionalProperties error points at the CONTAINER; the useful location
    # is the offending key itself, so append it.
    if err.validator == "additionalProperties":
        extra = _unexpected_keys(err)
        if extra:
            return f"{path}.{extra[0]}"
    return path


def _exclusive_choice_message(err: jsonschema.ValidationError) -> str | None:
    """Rephrase a failed `oneOf` over `required` lists in the author's terms.

    The schema says "exactly one form key" as a ``oneOf`` of eight one-key
    ``required`` clauses. jsonschema renders a violation as "is valid under each
    of {'required': ['text']}, {'required': ['ann']}", which is accurate,
    unreadable, and describes the schema rather than the mistake. Both ways of
    getting it wrong — two form keys, or none — are ordinary authoring slips and
    deserve a sentence.
    """
    # Guard on the validator, not just the schema shape: an `additionalProperties`
    # failure carries the SAME subschema (which also holds the `oneOf`), and
    # rephrasing that one would answer a question the author did not ask while
    # hiding the key they actually mistyped.
    if err.validator != "oneOf":
        return None
    branches = err.schema.get("oneOf") if isinstance(err.schema, dict) else None
    if not branches or not all(
        isinstance(b, dict) and set(b) == {"required"} and len(b["required"]) == 1
        for b in branches
    ):
        return None

    choices = [b["required"][0] for b in branches]
    present = (
        sorted(set(err.instance) & set(choices))
        if isinstance(err.instance, dict)
        else []
    )
    if len(present) > 1:
        problem = f"found {len(present)} of them ({', '.join(present)})"
    else:
        problem = "found none"
    return f"needs exactly one of {' | '.join(choices)} — {problem}"


def _schema_diagnostics(
    document: Any, *, source: str, pack: str
) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for err in sorted(_validator().iter_errors(document), key=lambda e: list(e.path)):
        rephrased = _exclusive_choice_message(err)
        leaf = err if rephrased else _most_specific(err)
        message = rephrased or leaf.message
        diagnostics.append(
            error(
                NLS_PACK_001,
                f"{_path_of(leaf)}: {message}",
                source=source,
                pack=pack,
            )
        )
    return diagnostics


def _most_specific(err: jsonschema.ValidationError) -> jsonschema.ValidationError:
    """Descend into ``oneOf``/``anyOf`` context to the error worth showing.

    A failure under ``oneOf`` reports as "is not valid under any of the given
    schemas", which tells a pack author nothing. The sub-error that reached
    deepest into their document is almost always the one they need, so prefer
    depth, then jsonschema's own relevance ordering as the tie-break.
    """
    while err.context:
        err = max(
            err.context,
            key=lambda sub: (
                len(list(sub.absolute_path)),
                jsonschema.exceptions.relevance(sub),
            ),
        )
    return err


def _pydantic_path(loc: tuple[Any, ...]) -> str:
    """Render a pydantic error location the way the schema errors render theirs.

    Pydantic splices its own machinery into `loc` — validator wrappers like
    ``function-after[_min_le_max(), RepeatModel]`` and union-member tags. Those
    describe *our* implementation, not the author's document, and a pack author
    reading `$.phases[0]…repeat.function-after[…]` learns nothing from the tail.
    Drop those segments and use the same `$.a[0].b` shape jsonschema produces, so
    both halves of validation point at a file the same way.
    """
    path = "$"
    for part in loc:
        if isinstance(part, int):
            path += f"[{part}]"
        elif "[" in part:
            continue  # pydantic internals
        else:
            path += f".{part}"
    return path


#: Trailing `loc` segments pydantic adds to name which arm of a union failed.
_UNION_TAGS = frozenset(
    {"str", "int", "float", "bool", "none", "NoneType", "function-after"}
)


def _strip_union_tag(loc: tuple[Any, ...]) -> tuple[Any, ...]:
    if loc and isinstance(loc[-1], str) and loc[-1] in _UNION_TAGS:
        return loc[:-1]
    return loc


def _pydantic_diagnostics(
    exc: ValidationError, *, source: str, pack: str
) -> list[Diagnostic]:
    """Turn pydantic's errors into diagnostics a pack author can act on.

    Two pieces of noise get removed. A union field reports once per arm, so
    ``repeat: {min: 5, max: 2}`` yields both "min is greater than max" and
    "Input should be a valid string" — the second is pydantic explaining that
    the value is not the `*`/`+`/`?` sugar, which the author did not attempt.
    Where a location has a message from one of our own validators, that message
    is the answer and the generic type complaints beside it are dropped.
    """
    grouped: dict[str, list[tuple[str, str]]] = {}
    for err in exc.errors():
        path = _pydantic_path(_strip_union_tag(err["loc"]))
        # Pydantic prefixes custom ValueErrors with "Value error, ".
        message = err["msg"].removeprefix("Value error, ")
        grouped.setdefault(path, []).append((err["type"], message))

    diagnostics = []
    for path, entries in grouped.items():
        authored = [msg for kind, msg in entries if kind == "value_error"]
        for message in authored or [msg for _, msg in entries]:
            # A validator that already located itself should not be located twice.
            text = message if message.startswith("$") else f"{path}: {message}"
            diagnostics.append(error(NLS_PACK_001, text, source=source, pack=pack))
    return diagnostics


def load_pack(path_or_str: str | Path, *, source: str = "") -> PackModel:
    """Parse and validate ONE pack.

    Args:
        path_or_str: A path to a ``*.pack.yaml`` file, or the YAML text itself.
        source: Where the pack came from, for diagnostics. Defaults to the path.

    Returns:
        The validated ``PackModel``.

    Raises:
        PackError: With every ``NLS-PACK-001`` diagnostic found, each naming the
            JSON path of the offending node.
    """
    text, resolved_source = _read(path_or_str, source)

    try:
        document = yaml.load(text, Loader=PackLoader)  # noqa: S506 — PackLoader is SafeLoader
    except yaml.YAMLError as exc:
        raise PackError(
            [
                error(
                    NLS_PACK_001,
                    f"$: YAML is not well-formed: {exc}",
                    source=resolved_source,
                )
            ]
        ) from exc

    if not isinstance(document, dict):
        raise PackError(
            [
                error(
                    NLS_PACK_001,
                    "$: a pack must be a YAML mapping, found "
                    f"{type(document).__name__}",
                    source=resolved_source,
                )
            ]
        )

    # Best-effort id, so that diagnostics about a broken pack can still name it.
    pack_id = document.get("pack") if isinstance(document.get("pack"), str) else ""

    diagnostics = _schema_diagnostics(document, source=resolved_source, pack=pack_id)
    if diagnostics:
        raise PackError(diagnostics)

    try:
        return PackModel.model_validate(document)
    except ValidationError as exc:
        raise PackError(
            _pydantic_diagnostics(exc, source=resolved_source, pack=pack_id)
        ) from exc


def _read(path_or_str: str | Path, source: str) -> tuple[str, str]:
    if isinstance(path_or_str, Path):
        return path_or_str.read_text(encoding="utf-8"), source or str(path_or_str)
    # A string is YAML unless it looks like a path to something that exists.
    if "\n" not in path_or_str:
        candidate = Path(path_or_str)
        if candidate.exists():
            return candidate.read_text(encoding="utf-8"), source or str(candidate)
    return path_or_str, source or "<string>"


__all__ = [
    "ActionModel",
    "AddActionModel",
    "GetterModel",
    "GroupModel",
    "InConstraint",
    "PackModel",
    "PhaseModel",
    "RangeConstraint",
    "RegexConstraint",
    "RepeatModel",
    "RuleModel",
    "StepModel",
    "UpdateActionModel",
    "load_pack",
    "load_schema",
]
