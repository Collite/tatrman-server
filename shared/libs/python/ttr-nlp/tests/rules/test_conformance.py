# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.2.T1 — the JAPE-semantics conformance matrix.

Arc gate 1. Every case is a hand-built document and a small pack, so nothing
here depends on an engine, a model, or a network: what is under test is the
*semantics*, and semantics should be reproducible on a laptop with no
dependencies beyond the wheel.

The matrix is the specification. If a case here disagrees with the code, the
code is wrong — these are JAPE's rules, not ours, and the whole point of the
effort is that a pack author who knows GATE can predict what a pack will do.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import pytest
from gatenlp import Document

from ttrnlp.rules.actions import apply_firings
from ttrnlp.rules.compiler import compile_pack
from ttrnlp.rules.dsl import load_pack
from ttrnlp.rules.executor import run_rules
from ttrnlp.rules.pipeline import run_phases, visible_annotations

# ── harness ──────────────────────────────────────────────────────────────────

Annotation = tuple[int, int, str, dict]


@dataclass
class Case:
    """One conformance case: a document, a pack, and what must come out."""

    id: str
    text: str
    anns: list[Annotation]
    pack: str
    #: (rule id, start, end) for each firing, in order.
    firings: list[tuple[str, int, int]] | None = None
    #: (set name, type, start, end, features that must be present).
    added: list[tuple[str, str, int, int, dict]] | None = None
    #: Features that must be present on an existing annotation after the run.
    updated: list[tuple[str, int, dict]] = field(default_factory=list)


def build_document(text: str, anns: list[Annotation]) -> Document:
    doc = Document(text)
    annset = doc.annset("")
    for start, end, anntype, features in anns:
        annset.add(start, end, anntype, dict(features))
    return doc


def pack_yaml(
    lhs: str,
    rhs: str = "[ { add: { type: M } } ]",
    *,
    control: str = "appelt",
    inputs: str = "[T]",
    rule: str = "R",
    priority: int | None = None,
    extra_rules: str = "",
) -> str:
    priority_line = f"        priority: {priority}\n" if priority is not None else ""
    return (
        "pack: conformance\nversion: 1\nphases:\n"
        f"  - phase: p\n    input: {inputs}\n    control: {control}\n"
        "    rules:\n"
        f"      - rule: {rule}\n"
        f"{priority_line}"
        f"        lhs: {lhs}\n"
        f"        rhs: {rhs}\n"
        f"{extra_rules}"
    )


def rule_yaml(
    name: str,
    lhs: str,
    rhs: str = "[ { add: { type: M } } ]",
    priority: int | None = None,
) -> str:
    priority_line = f"        priority: {priority}\n" if priority is not None else ""
    return (
        f"      - rule: {name}\n{priority_line}"
        f"        lhs: {lhs}\n        rhs: {rhs}\n"
    )


def run_case(case: Case):
    """Run a case and return (document, firings).

    Deliberately drives the executor directly rather than going through
    ``run_phases``: which rule won is the thing under test, and inferring that
    from the annotations afterwards cannot distinguish two rules that add the
    same type. ``test_run_phases_matches_the_direct_path`` keeps the two in
    step.
    """
    doc = build_document(case.text, case.anns)
    compiled = compile_pack(load_pack(case.pack))
    (phase,) = compiled.phases
    annotations = visible_annotations(doc, phase)
    firings = run_rules(doc, annotations, phase.rules, phase.control)
    apply_firings(doc, firings)
    return doc, firings


# ── the matrix ───────────────────────────────────────────────────────────────
#
# Text used by most cases: "a b c d e", so T annotations sit at 0-1, 2-3, 4-5,
# 6-7, 8-9 with a single space between each.

TEXT = "a b c d e"
T3 = [(0, 1, "T", {"n": 1}), (2, 3, "T", {"n": 2}), (4, 5, "T", {"n": 3})]
T2 = T3[:2]

ONE_T = "[ { ann: T } ]"
TWO_T = "[ { ann: T }, { ann: T } ]"

CASES: list[Case] = [
    # ── appelt: the tie-break, one clause at a time ──────────────────────────
    Case(
        # The clause PAMPAC cannot express: a LOWER-priority rule that matches
        # more text beats a higher-priority shorter one.
        id="appelt-longest-beats-priority",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(ONE_T, rule="Short", priority=100)
        + rule_yaml("Long", TWO_T, priority=0),
        firings=[("Long", 0, 3)],
    ),
    Case(
        id="appelt-priority-beats-fileorder",
        text=TEXT,
        anns=[T3[0]],
        pack=pack_yaml(ONE_T, rule="Earlier", priority=0)
        + rule_yaml("Later", ONE_T, priority=50),
        firings=[("Later", 0, 1)],
    ),
    Case(
        id="appelt-fileorder-last",
        text=TEXT,
        anns=[T3[0]],
        pack=pack_yaml(ONE_T, rule="Alpha", priority=7)
        + rule_yaml("Beta", ONE_T, priority=7),
        firings=[("Alpha", 0, 1)],
    ),
    Case(
        # Resume is AFTER the match: the middle T is consumed, so the rule
        # cannot fire again using it.
        id="appelt-region-resume",
        text=TEXT,
        anns=T3,
        pack=pack_yaml(TWO_T),
        firings=[("R", 0, 3)],
    ),
    # ── the other four control styles ────────────────────────────────────────
    Case(
        id="brill-all-fire-resume-maxend",
        text=TEXT,
        anns=T3,
        pack=pack_yaml(ONE_T, control="brill", rule="One")
        + rule_yaml("Two", TWO_T),
        firings=[("One", 0, 1), ("Two", 0, 3), ("One", 4, 5)],
    ),
    Case(
        id="all-resume-next-offset",
        text=TEXT,
        anns=T3,
        pack=pack_yaml(ONE_T, control="all", rule="One") + rule_yaml("Two", TWO_T),
        firings=[
            ("One", 0, 1),
            ("Two", 0, 3),
            ("One", 2, 3),
            ("Two", 2, 5),
            ("One", 4, 5),
        ],
    ),
    Case(
        # `first` fires the first rule in file order that accepts and does NOT
        # go looking for a longer match. The same two rules under appelt would
        # give ("Two", 0, 3) — see appelt-longest-beats-priority.
        id="first-no-extension",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(ONE_T, control="first", rule="One") + rule_yaml("Two", TWO_T),
        firings=[("One", 0, 1), ("One", 2, 3)],
    ),
    Case(
        id="once-stops-phase",
        text=TEXT,
        anns=T3,
        pack=pack_yaml(ONE_T, control="once"),
        firings=[("R", 0, 1)],
    ),
    # ── input: visibility, the gap-skipping device ───────────────────────────
    Case(
        # Two Lookups with a Token between them match as neighbours, because the
        # phase cannot see Tokens at all.
        id="input-gap-skip",
        text=TEXT,
        anns=[(0, 1, "Lookup", {}), (2, 3, "Token", {}), (4, 5, "Lookup", {})],
        pack=pack_yaml(
            "[ { ann: Lookup }, { ann: Lookup } ]", inputs="[Lookup]"
        ),
        firings=[("R", 0, 5)],
    ),
    Case(
        # The same document and the same rule, with ORG added to `input:` — now
        # it is visible, sits between the two Lookups, and blocks the match.
        # This is the contrast that proves visibility is doing the work.
        id="input-unlisted-invisible",
        text=TEXT,
        anns=[(0, 1, "Lookup", {}), (2, 3, "ORG", {}), (4, 5, "Lookup", {})],
        pack=pack_yaml(
            "[ { ann: Lookup }, { ann: Lookup } ]", inputs="[Lookup, ORG]"
        ),
        firings=[],
    ),
    Case(
        # A KNOWN BOUNDARY with real consequences for pack authors, pinned here
        # because it is invisible until it costs someone an afternoon.
        #
        # PAMPAC's `Ann` matches THE NEXT annotation in the visible list and
        # will not skip a non-matching one. Two annotations at the same start
        # offset are ordered by insertion, so a `Lookup` that a gazetteer added
        # over a `Token` sits AFTER that Token — and if both are in `input:`,
        # the Lookup can never be the next annotation at that offset. It is not
        # merely harder to match; it is unreachable.
        #
        # The rule that follows: `input:` lists ONE layer. The design's own hero
        # does exactly that (`input: [Lookup, ORG, PER]`, no Token). To bring a
        # token-level signal into a Lookup-level phase, lift it in an earlier
        # phase — see tests/fixtures/packs/valid/hero-cs-invoices.pack.yaml.
        id="coextensive-annotation-shadows-a-later-one",
        text=TEXT,
        anns=[(0, 1, "T", {}), (0, 1, "Lookup", {})],
        pack=pack_yaml("[ { ann: Lookup } ]", inputs="[Lookup, T]", control="brill"),
        firings=[],
    ),
    Case(
        # …and the same document with `T` left out of `input:` matches fine.
        id="coextensive-shadowing-goes-away-with-one-layer-in-input",
        text=TEXT,
        anns=[(0, 1, "T", {}), (0, 1, "Lookup", {})],
        pack=pack_yaml("[ { ann: Lookup } ]", inputs="[Lookup]", control="brill"),
        firings=[("R", 0, 1)],
    ),
    # ── bindings ─────────────────────────────────────────────────────────────
    Case(
        id="bind-step",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { ann: T }, { ann: T, bind: second } ]",
            "[ { add: { type: M, span: second } } ]",
        ),
        added=[("", "M", 2, 3, {})],
    ),
    Case(
        id="bind-group",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { group: { seq: [ { ann: T }, { ann: T } ] }, bind: both } ]",
            "[ { add: { type: M, span: both } } ]",
        ),
        added=[("", "M", 0, 3, {})],
    ),
    # ── structure ────────────────────────────────────────────────────────────
    Case(
        id="or-branches",
        text=TEXT,
        anns=[(0, 1, "A", {}), (2, 3, "B", {})],
        pack=pack_yaml(
            "[ { group: { or: [ [ { ann: A } ], [ { ann: B } ] ] } } ]",
            inputs="[A, B]",
            control="brill",
        ),
        firings=[("R", 0, 1), ("R", 2, 3)],
    ),
    Case(
        id="seq-nested",
        text=TEXT,
        anns=T3,
        pack=pack_yaml(
            "[ { group: { seq: [ { ann: T }, "
            "{ group: { seq: [ { ann: T }, { ann: T } ] } } ] } } ]"
        ),
        firings=[("R", 0, 5)],
    ),
    Case(
        id="repeat-range",
        text=TEXT,
        anns=T3,
        pack=pack_yaml("[ { ann: T, repeat: { min: 2, max: 3 } } ]"),
        firings=[("R", 0, 5)],
    ),
    Case(
        # min 0 makes the repeat optional: the rule still fires on the trailing
        # anchor with zero Ts consumed before it.
        id="repeat-zero-min",
        text=TEXT,
        anns=[(0, 1, "A", {}), (2, 3, "T", {})],
        pack=pack_yaml(
            "[ { ann: T, repeat: { min: 0, max: 2 } }, { ann: A } ]",
            inputs="[T, A]",
        ),
        firings=[("R", 0, 1)],
    ),
    Case(
        # A KNOWN BOUNDARY, pinned so it is documented rather than discovered.
        # `repeat` is greedy and does not backtrack: PAMPAC's `N` yields a
        # result only when it hits `max` or when the inner parser fails, never
        # an intermediate count. So a repeat followed by the same type consumes
        # everything and leaves the anchor nothing — the rule matches NOTHING
        # rather than giving one annotation back. Write the anchor with a
        # different type, or bound the repeat.
        id="repeat-is-greedy-and-does-not-backtrack",
        text=TEXT,
        anns=T3,
        pack=pack_yaml("[ { ann: T, repeat: '*' }, { ann: T } ]"),
        firings=[],
    ),
    # ── containment ──────────────────────────────────────────────────────────
    Case(
        id="contains",
        text=TEXT,
        anns=[(0, 5, "Lookup", {}), (2, 3, "T", {})],
        pack=pack_yaml(
            "[ { ann: Lookup, contains: { ann: T } } ]", inputs="[Lookup, T]"
        ),
        firings=[("R", 0, 5)],
    ),
    Case(
        id="contains-negative",
        text=TEXT,
        anns=[(0, 5, "Lookup", {}), (6, 7, "T", {})],
        pack=pack_yaml(
            "[ { ann: Lookup, contains: { ann: T } } ]", inputs="[Lookup, T]"
        ),
        firings=[],
    ),
    Case(
        id="within",
        text=TEXT,
        anns=[(0, 9, "S", {}), (2, 3, "T", {})],
        pack=pack_yaml("[ { ann: T, within: { ann: S } } ]", inputs="[T, S]"),
        firings=[("R", 2, 3)],
    ),
    Case(
        id="within-negative",
        text=TEXT,
        anns=[(0, 1, "S", {}), (2, 3, "T", {})],
        pack=pack_yaml("[ { ann: T, within: { ann: S } } ]", inputs="[T, S]"),
        firings=[],
    ),
    # ── conjunction and negation ─────────────────────────────────────────────
    Case(
        id="all-plus-not",
        text=TEXT,
        anns=[(0, 1, "T", {"kind": "keep"}), (2, 3, "T", {"kind": "drop"})],
        pack=pack_yaml(
            "[ { all: [ { ann: T }, "
            "{ not: { ann: T, features: { kind: drop } } } ] } ]",
            control="brill",
        ),
        firings=[("R", 0, 1)],
    ),
    # ── zero-width lookahead ─────────────────────────────────────────────────
    Case(
        # The lookahead asserts a following A and is EXCLUDED from the span:
        # the match ends at 1, not at 3.
        id="after",
        text=TEXT,
        anns=[(0, 1, "T", {}), (2, 3, "A", {})],
        pack=pack_yaml(
            "[ { ann: T }, { after: { ann: A } } ]", inputs="[T, A]"
        ),
        firings=[("R", 0, 1)],
    ),
    Case(
        id="after-negative",
        text=TEXT,
        anns=[(0, 1, "T", {}), (2, 3, "B", {})],
        pack=pack_yaml(
            "[ { ann: T }, { after: { ann: A } } ]", inputs="[T, A, B]"
        ),
        firings=[],
    ),
    Case(
        id="notafter",
        text=TEXT,
        anns=[(0, 1, "T", {}), (2, 3, "B", {})],
        pack=pack_yaml(
            "[ { ann: T }, { notafter: { ann: A } } ]", inputs="[T, A, B]"
        ),
        firings=[("R", 0, 1)],
    ),
    Case(
        id="notafter-blocks",
        text=TEXT,
        anns=[(0, 1, "T", {}), (2, 3, "A", {})],
        pack=pack_yaml(
            "[ { ann: T }, { notafter: { ann: A } } ]", inputs="[T, A]"
        ),
        firings=[],
    ),
    # ── RHS ──────────────────────────────────────────────────────────────────
    Case(
        id="add-whole-match",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(TWO_T, "[ { add: { type: M } } ]"),
        added=[("", "M", 0, 3, {})],
    ),
    Case(
        id="add-span-bind",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { ann: T, bind: first }, { ann: T } ]",
            "[ { add: { type: M, span: first } } ]",
        ),
        added=[("", "M", 0, 1, {})],
    ),
    Case(
        id="add-getter-string",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { ann: T }, { ann: T, bind: it } ]",
            '[ { add: { type: M, features: { v: { from: it, get: "@string" } } } } ]',
        ),
        added=[("", "M", 0, 3, {"v": "b"})],
    ),
    Case(
        id="add-getter-length",
        text=TEXT,
        anns=[(0, 1, "T", {}), (2, 5, "T", {})],
        pack=pack_yaml(
            "[ { ann: T }, { ann: T, bind: it } ]",
            '[ { add: { type: M, features: { v: { from: it, get: "@length" } } } } ]',
        ),
        added=[("", "M", 0, 5, {"v": 3})],
    ),
    Case(
        id="add-getter-feature",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { ann: T }, { ann: T, bind: it } ]",
            "[ { add: { type: M, features: { v: { from: it, get: n } } } } ]",
        ),
        added=[("", "M", 0, 3, {"v": 2})],
    ),
    Case(
        id="update-on-bind",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(
            "[ { ann: T, bind: it }, { ann: T } ]",
            "[ { update: { on: it, features: { seen: true } } } ]",
        ),
        updated=[("T", 0, {"seen": True, "n": 1})],
    ),
    Case(
        id="add-to-named-set",
        text=TEXT,
        anns=T2,
        pack=pack_yaml(TWO_T, "[ { add: { type: M, set: patterns } } ]"),
        added=[("patterns", "M", 0, 3, {})],
    ),
]


@pytest.mark.parametrize("case", CASES, ids=[c.id for c in CASES])
def test_conformance(case: Case):
    doc, firings = run_case(case)

    if case.firings is not None:
        actual = [(f.rule.rule, f.start, f.end) for f in firings]
        assert actual == case.firings

    for set_name, anntype, start, end, features in case.added or []:
        candidates = [
            ann
            for ann in doc.annset(set_name).with_type(anntype)
            if (ann.start, ann.end) == (start, end)
        ]
        assert candidates, (
            f"{case.id}: no {anntype} at ({start},{end}) in set {set_name!r}; "
            f"found {[(a.type, a.start, a.end) for a in doc.annset(set_name)]}"
        )
        for key, value in features.items():
            assert candidates[0].features.get(key) == value

    for anntype, start, features in case.updated:
        ann = next(
            a for a in doc.annset("").with_type(anntype) if a.start == start
        )
        for key, value in features.items():
            assert ann.features.get(key) == value


# ── T8: why the custom executor exists ───────────────────────────────────────


def test_stock_pampac_disagrees_with_appelt_on_longest_beats_priority():
    """NLS-P1.2.T8 — the NL-13 tripwire.

    The same compiled rules, run by stock ``Pampac`` instead of our executor,
    pick the *other* rule. That difference is the entire justification for
    ``executor.py``, so it is asserted rather than described: if a future
    gatenlp ever implements longest-then-priority selection, this test fails and
    tells us the custom executor can be reconsidered.
    """
    from gatenlp.pam.pampac import Pampac, Rule

    case = next(c for c in CASES if c.id == "appelt-longest-beats-priority")
    doc = build_document(case.text, case.anns)
    compiled = compile_pack(load_pack(case.pack))
    (phase,) = compiled.phases
    annotations = visible_annotations(doc, phase)

    fired: list[str] = []

    def recorder(name):
        def action(_success, **_kwargs):
            fired.append(name)

        return action

    stock = Pampac(
        *[
            Rule(rule.parser, recorder(rule.rule), priority=rule.priority)
            for rule in phase.rules
        ],
        skip="longest",
        select="highest",
    )
    stock.run(doc, annotations)

    # Stock PAMPAC only ever fires the SHORTER, higher-priority rule:
    # `select="highest"` resolves to the globally-highest-priority rule and
    # never compares lengths. Having consumed only one T it then resumes and
    # fires again on the second — two short matches where appelt sees one long.
    assert fired == ["Short", "Short"]
    assert "Long" not in fired

    ours = run_rules(doc, annotations, phase.rules, phase.control)
    assert [f.rule.rule for f in ours] == ["Long"]


# ── the two paths agree ──────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "case", [c for c in CASES if c.added], ids=[c.id for c in CASES if c.added]
)
def test_run_phases_matches_the_direct_path(case: Case):
    """`run_phases` is what the service calls; the matrix drives the executor
    directly. They must not be allowed to drift."""
    direct_doc, _ = run_case(case)
    phase_doc = build_document(case.text, case.anns)
    run_phases(phase_doc, [compile_pack(load_pack(case.pack))])

    def snapshot(doc):
        return sorted(
            (name, a.type, a.start, a.end, dict(a.features))
            for name in ("", "patterns")
            for a in doc.annset(name)
        )

    assert snapshot(phase_doc) == snapshot(direct_doc)


def test_the_phase_report_counts_what_happened():
    case = next(c for c in CASES if c.id == "brill-all-fire-resume-maxend")
    doc = build_document(case.text, case.anns)
    report = run_phases(doc, [compile_pack(load_pack(case.pack))])

    (trace,) = report.traces
    assert trace.phase == "p"
    assert trace.kind == "rules"
    assert trace.firings == 3
    assert trace.annotations_added == 3
    assert report.annotations_added == 3
    assert trace.elapsed_ms >= 0


def test_a_later_phase_sees_what_an_earlier_one_added():
    """Phases re-read the annotation set — that is the point of having them."""
    pack = (
        "pack: two-phase\nversion: 1\nphases:\n"
        "  - phase: first\n    input: [T]\n    control: brill\n"
        "    rules:\n"
        "      - rule: Mark\n        lhs: [ { ann: T } ]\n"
        "        rhs: [ { add: { type: Marked } } ]\n"
        "  - phase: second\n    input: [Marked]\n    control: brill\n"
        "    rules:\n"
        "      - rule: Consume\n        lhs: [ { ann: Marked } ]\n"
        "        rhs: [ { add: { type: Final } } ]\n"
    )
    doc = build_document(TEXT, T2)
    report = run_phases(doc, [compile_pack(load_pack(pack))])

    assert [t.phase for t in report.traces] == ["first", "second"]
    assert len(list(doc.annset("").with_type("Marked"))) == 2
    assert len(list(doc.annset("").with_type("Final"))) == 2


def test_phases_can_be_restricted_by_name():
    pack = (
        "pack: two-phase\nversion: 1\nphases:\n"
        "  - phase: first\n    input: [T]\n    control: brill\n"
        "    rules:\n"
        "      - rule: Mark\n        lhs: [ { ann: T } ]\n"
        "        rhs: [ { add: { type: Marked } } ]\n"
        "  - phase: second\n    input: [T]\n    control: brill\n"
        "    rules:\n"
        "      - rule: Other\n        lhs: [ { ann: T } ]\n"
        "        rhs: [ { add: { type: Other } } ]\n"
    )
    doc = build_document(TEXT, T2)
    report = run_phases(doc, [compile_pack(load_pack(pack))], phases=["second"])

    assert [t.phase for t in report.traces] == ["second"]
    assert not list(doc.annset("").with_type("Marked"))
    assert len(list(doc.annset("").with_type("Other"))) == 2


def test_the_matrix_is_at_least_the_promised_size():
    # The plan's gate is >= 25 cases. Guarding the count stops the matrix being
    # quietly thinned when a case becomes inconvenient.
    assert len(CASES) >= 25
    assert len({c.id for c in CASES}) == len(CASES), "duplicate case ids"
