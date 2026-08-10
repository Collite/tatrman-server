# SPDX-License-Identifier: Apache-2.0
"""NLS-P1.2 — the compiler, and what it refuses to compile.

Two obligations, and they pull in opposite directions.

**Everything the DSL admits must actually compile and match.** Loading a pack is
not the same as being able to run it, and the difference is where the interesting
bugs live: the parser tree is where a construct meets PAMPAC's real API, and PAMPAC
does not name every parser, does not let ``Seq`` hold one member, and does not
notice a repetition that cannot terminate. So the coverage fixtures are compiled
here — ``load_pack`` alone would have let every failure below ship green.

**A pack that compiles must never fire and write nothing.** That is the failure
mode ``checks.py`` exists to prevent, and the compiler owns the half of it that
depends on how the tree is built rather than on how the pack refers to itself: a
``bind:`` in a position whose match info is discarded, and a ``repeat`` over a form
that can match without consuming anything. Both are ``NLS-PACK-002``.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from gatenlp import Document

from ttrnlp.packs.diag import NLS_PACK_002, PackError
from ttrnlp.rules import build_pack, compile_pack
from ttrnlp.rules.dsl import load_pack
from ttrnlp.rules.pipeline import run_phases

VALID = Path(__file__).parent.parent / "fixtures" / "packs" / "valid"

TEXT = "a b c"
#: T at 0-1, 2-3, 4-5 — one space between each.
T3 = [(0, 1, "T", {"n": 1}), (2, 3, "T", {"n": 2}), (4, 5, "T", {"n": 3})]


def pack_yaml(lhs: str, rhs: str = "[ { add: { type: M } } ]", *, inputs: str = "[T]"):
    return (
        "pack: compiler\nversion: 1\nphases:\n"
        f"  - phase: p\n    input: {inputs}\n    control: appelt\n"
        "    rules:\n"
        f"      - rule: R\n        lhs: {lhs}\n        rhs: {rhs}\n"
    )


def document(anns=T3, text: str = TEXT) -> Document:
    doc = Document(text)
    annset = doc.annset("")
    for start, end, anntype, features in anns:
        annset.add(start, end, anntype, dict(features))
    return doc


def run(pack: str, anns=T3, text: str = TEXT):
    """Compile a pack, run it over a document, and return (doc, report)."""
    doc = document(anns, text)
    report = run_phases(doc, [compile_pack(load_pack(pack))])
    return doc, report


def spans(doc, anntype: str) -> list[tuple[int, int]]:
    return sorted((a.start, a.end) for a in doc.annset("").with_type(anntype))


def diagnostics(pack: str) -> list[str]:
    """Compile a pack that must fail, and return its messages."""
    with pytest.raises(PackError) as raised:
        compile_pack(load_pack(pack))
    assert raised.value.codes == [NLS_PACK_002] * len(raised.value.diagnostics)
    return [d.message for d in raised.value.diagnostics]


# ── the coverage fixtures have to compile, not merely load ───────────────────


@pytest.mark.parametrize(
    "path", sorted(VALID.glob("*.pack.yaml")), ids=lambda p: p.stem
)
def test_every_valid_fixture_builds(path):
    """``build_pack`` — load, cross-check, normalise AND compile — on each fixture.

    The sweep next to this one in ``test_schema.py`` stops at ``load_pack``. A
    fixture whose whole purpose is "every construct the DSL admits, in one pack"
    has not shown that until the constructs reach a parser tree.
    """
    compiled = build_pack(path)
    assert compiled.phases
    assert all(rule.parser is not None for p in compiled.phases for rule in p.rules)


def test_the_design_hero_pack_compiles():
    """The pack contracts §3 shows to analysts. If this cannot be compiled, the
    reference case is not a reference case."""
    compiled = build_pack(VALID / "hero.pack.yaml")
    (phase,) = compiled.phases
    (rule,) = phase.rules
    assert rule.annotation_binds == {"name": False}  # the `or` group binds a span


# ── bindings PAMPAC will not name for us ─────────────────────────────────────


def test_a_bind_on_an_alternation_records_the_matched_span():
    """The hero pack's shape. ``Or`` takes no ``name=`` and ``Seq`` refuses to hold
    one parser, so this is the case that needs ``Named``."""
    doc, report = run(
        pack_yaml(
            "[ { group: { or: [ [ { ann: T, features: { n: 2 } } ], "
            "[ { ann: T, features: { n: 3 } } ] ] }, bind: b } ]",
            "[ { add: { type: M, span: b } } ]",
        )
    )
    assert report.firings == 2
    assert spans(doc, "M") == [(2, 3), (4, 5)]


@pytest.mark.parametrize("key", ["contains", "within"])
def test_a_bind_survives_a_containment_filter_as_an_annotation(key):
    """``covering()``/``within()`` return a ``Filter``, which has no ``name=``.

    Naming the ``Ann`` underneath rather than wrapping the filter is what keeps
    the binding an ANNOTATION binding: only PAMPAC's own ``Ann`` match entry
    carries ``ann=``, and ``update:`` reads exactly that.
    """
    # L covers T either way; which of the two the rule matches is what differs.
    anns = [(0, 5, "L", {}), (2, 3, "T", {})]
    outer, inner = ("L", "T") if key == "contains" else ("T", "L")
    doc, report = run(
        pack_yaml(
            f"[ {{ ann: {outer}, {key}: {{ ann: {inner} }}, bind: b }} ]",
            "[ { update: { on: b, features: { seen: yes-it-was } } } ]",
            inputs="[T, L]",
        ),
        anns=anns,
    )
    assert report.firings == 1
    bound = next(a for a in doc.annset("").with_type(outer))
    assert bound.features["seen"] == "yes-it-was"


@pytest.mark.parametrize(
    ("shape", "lhs"),
    [
        ("group", "[ { group: { seq: [ { ann: T, bind: inner } ] }, bind: outer } ]"),
        ("all", "[ { all: [ { ann: T, bind: inner } ], bind: outer } ]"),
    ],
)
def test_an_outer_bind_does_not_erase_the_inner_one(shape, lhs):
    """A one-step group and a one-member ``all:`` hand their child's parser straight
    back to the binder. Assigning the outer name over it used to leave the rule
    firing and writing nothing at all — which nothing in the pack could reveal."""
    doc, report = run(
        pack_yaml(
            lhs,
            "[ { add: { type: M, span: inner } }, "
            "{ add: { type: N, span: outer } } ]",
        )
    )
    assert report.firings == 3
    assert spans(doc, "M") == [(0, 1), (2, 3), (4, 5)]
    assert spans(doc, "N") == [(0, 1), (2, 3), (4, 5)]


def test_a_bind_on_a_zero_width_step_itself_is_allowed():
    """Only a bind INSIDE the assertion is unreadable. On the assertion step it
    records the position the assertion held, which is a legitimate empty span."""
    compiled = compile_pack(
        load_pack(pack_yaml("[ { after: { ann: T }, bind: b }, { ann: T } ]"))
    )
    (rule,) = compiled.phases[0].rules
    assert rule.annotation_binds == {"b": False}


# ── bindings whose match info is discarded ───────────────────────────────────


@pytest.mark.parametrize("key", ["contains", "within"])
def test_a_bind_inside_a_containment_filter_is_rejected(key):
    (message,) = diagnostics(
        pack_yaml(
            f"[ {{ ann: T, {key}: {{ ann: T, bind: inner }} }} ]",
            "[ { add: { type: M, span: inner } } ]",
        )
    )
    assert "bind: inner" in message
    assert key in message


@pytest.mark.parametrize(
    ("form", "lhs"),
    [
        ("after", "[ { after: { ann: T, bind: inner } }, { ann: T } ]"),
        ("notafter", "[ { notafter: { ann: U, bind: inner } }, { ann: T } ]"),
        ("not", "[ { all: [ { ann: T }, { not: { ann: U, bind: inner } } ] } ]"),
    ],
)
def test_a_bind_inside_a_zero_width_assertion_is_rejected(form, lhs):
    (message,) = diagnostics(
        pack_yaml(lhs, "[ { add: { type: M, span: inner } } ]", inputs="[T, U]")
    )
    assert "bind: inner" in message
    assert form in message


def test_a_bind_nested_deep_inside_an_assertion_is_still_rejected():
    """The whole subtree is discarded, not just the immediate step."""
    (message,) = diagnostics(
        pack_yaml(
            "[ { after: { group: { seq: [ { ann: T, bind: inner } ] } } }, "
            "{ ann: T } ]",
            "[ { add: { type: M, span: inner } } ]",
        )
    )
    assert "bind: inner" in message


# ── repetitions that could never terminate ───────────────────────────────────


@pytest.mark.parametrize(
    ("case", "lhs"),
    [
        ("after", '[ { after: { ann: T }, repeat: "*" }, { ann: T } ]'),
        ("notafter", '[ { notafter: { ann: U }, repeat: "+" }, { ann: T } ]'),
        (
            "all-of-assertions",
            '[ { all: [ { after: { ann: T } } ], repeat: "*" }, { ann: T } ]',
        ),
        (
            "group-of-optionals",
            '[ { group: { seq: [ { ann: T, repeat: "?" } ] }, repeat: "*" } ]',
        ),
        (
            "or-with-an-optional-branch",
            '[ { group: { or: [ [ { ann: T, repeat: "?" } ], [ { ann: U } ] ] }, '
            'repeat: "*" } ]',
        ),
    ],
)
def test_repeat_over_a_form_that_can_match_nothing_is_rejected(case, lhs):
    """``N`` stops when its inner parser stops matching. One that succeeds at the
    same location every time never stops — a ``RecursionError`` under
    ``select="all"``, and 2**31-1 iterations otherwise. Either way a validated
    pack would take the phase down at match time, which is not a failure mode a
    pack author can be left to discover in production."""
    (message,) = diagnostics(pack_yaml(lhs, inputs="[T, U]"))
    assert "repeat" in message


@pytest.mark.parametrize(
    ("case", "lhs"),
    [
        ("ann", '[ { ann: T, repeat: "*" } ]'),
        (
            "seq-group",
            '[ { group: { seq: [ { ann: T }, { ann: T } ] }, repeat: "+" } ]',
        ),
        (
            "or-group",
            '[ { group: { or: [ [ { ann: T } ], [ { ann: T }, { ann: T } ] ] }, '
            'repeat: "*" } ]',
        ),
        ("all", '[ { all: [ { ann: T }, { not: { ann: U } } ], repeat: "+" } ]'),
        (
            "optional-inside-a-seq-that-consumes",
            '[ { group: { seq: [ { ann: T }, { ann: U, repeat: "?" } ] }, '
            'repeat: "+" } ]',
        ),
    ],
)
def test_repeat_over_a_form_that_consumes_still_compiles(case, lhs):
    """The other side of the guard: a repetition whose body must consume something
    terminates, and rejecting those would take most of the DSL's repeat with it."""
    compiled = compile_pack(load_pack(pack_yaml(lhs, inputs="[T, U]")))
    assert compiled.phases[0].rules[0].parser is not None
