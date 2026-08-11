# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.2.T1/T3 — the parity guarantee, and the ``--model`` lane.

**Parity is the load-bearing test in this file.** ``ttr-nlp validate`` is only
worth running if it answers the question the cluster will answer, and the way
that promise breaks is not dramatic: someone adds a check to the loader, or
tightens one, and the CLI drifts a release behind. So the assertion is
byte-identical diagnostics — same codes, same messages, same order, same
`source`/`pack` — over the same fixtures, taken from `validate_sources` on one
side and from the `LoadError` the loader raises on the other.

**The model lane (NLS-PACK-005) is deliberately CLI-only.** The service never
sees model files (contracts §5), so `load_sources` has no `model` parameter at
all — asserted here, because "the boundary" and "nobody got round to it" look
identical from the outside.
"""

from __future__ import annotations

import inspect
from pathlib import Path

import pytest

from ttrnlp.packs.diag import NLS_PACK_004, NLS_PACK_005
from ttrnlp.packs.loader import LoadError, load_sources
from ttrnlp.packs.validate import (
    read_model_queries,
    validate_sources,
)

from .test_loader import good_list, good_pack, three_good, tree

FIXTURES = Path(__file__).parent.parent / "fixtures"
VALID_PACKS = FIXTURES / "packs" / "valid"
INVALID_PACKS = FIXTURES / "packs" / "invalid"
VALID_LISTS = FIXTURES / "lists" / "valid"
INVALID_LISTS = FIXTURES / "lists" / "invalid"
MODEL = FIXTURES / "model"


# ── parity (T1) ──────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "sources",
    [
        pytest.param([str(INVALID_PACKS)], id="invalid-packs"),
        pytest.param([str(INVALID_LISTS)], id="invalid-lists"),
        pytest.param(
            [str(INVALID_PACKS), str(INVALID_LISTS)], id="both-kinds-at-once"
        ),
        pytest.param(["/definitely/not/here"], id="missing-source"),
    ],
)
def test_the_cli_path_and_the_loader_report_the_same_diagnostics(sources):
    """Identical diagnostics, not merely an identical verdict."""
    collected = validate_sources(sources)

    with pytest.raises(LoadError) as raised:
        load_sources(sources)

    assert collected == raised.value.diagnostics


def test_parity_holds_for_pipeline_references_too(tmp_path):
    """The `pipelines` argument exists on both, and must behave the same on both
    — this is the check the service runs at every reload."""
    root = str(three_good(tmp_path))
    pipelines = {"p": {"gazetteer": ["nope"], "rules": [{"pack": "x", "phase": "y"}]}}

    collected = validate_sources([root], pipelines=pipelines)
    with pytest.raises(LoadError) as raised:
        load_sources([root], pipelines=pipelines)

    assert collected == raised.value.diagnostics
    assert {d.code for d in collected} == {NLS_PACK_004}


def test_valid_sources_produce_no_diagnostics_at_all():
    assert validate_sources([str(VALID_PACKS), str(VALID_LISTS)]) == []


def test_the_service_facing_loader_has_no_model_parameter():
    """contracts §5: cross-checking against a model is a CLI-lane concern. The
    service is handed packs and answers questions about text; giving it a model
    directory would make it care about a schema it does not own.

    Asserted rather than trusted, because the absence has to read as a decision.
    """
    assert "model" not in inspect.signature(load_sources).parameters
    assert "model" in inspect.signature(validate_sources).parameters


# ── the model reader ─────────────────────────────────────────────────────────


def test_the_fixture_model_yields_its_queries_and_parameters():
    queries, notes = read_model_queries(MODEL)
    assert notes == []
    assert queries == {
        "faktury_zakaznika": {"nazev_zakaznika"},
        "role": {"nazev_role"},
    }


@pytest.mark.parametrize(
    ("body", "expected"),
    [
        pytest.param(
            "queries:\n  - id: q\n    parameters: [a, b]\n",
            {"q": {"a", "b"}},
            id="list-of-ids-plain-parameters",
        ),
        pytest.param(
            "queries:\n  - query: q\n    parameters:\n      - name: a\n",
            {"q": {"a"}},
            id="query-key-named-parameters",
        ),
        pytest.param(
            "queries:\n  q:\n    parameters:\n      a: {type: string}\n",
            {"q": {"a"}},
            id="mapping-of-ids-mapping-of-parameters",
        ),
        pytest.param(
            "queries:\n  - id: q\n",
            {"q": set()},
            id="a-query-may-take-no-parameters",
        ),
    ],
)
def test_the_reader_accepts_the_shapes_a_model_plausibly_uses(
    tmp_path, body, expected
):
    (tmp_path / "m.yaml").write_text(body, encoding="utf-8")
    queries, notes = read_model_queries(tmp_path)
    assert queries == expected
    assert notes == []


def test_the_reader_ignores_everything_it_does_not_understand(tmp_path):
    """The TTR-M schema belongs to `tatrman`. A strict reader here would couple a
    published wheel to a modeling language it does not own, and guessing wrong
    would block a push over a pack that is fine."""
    (tmp_path / "m.yaml").write_text(
        "model: dfp\nentities: [{id: x, attributes: [y]}]\n"
        "queries:\n  - id: q\n    parameters: [a]\n    unknown_key: whatever\n",
        encoding="utf-8",
    )
    queries, notes = read_model_queries(tmp_path)
    assert queries == {"q": {"a"}}
    assert notes == []


def test_an_unparseable_model_file_is_a_note_not_a_crash(tmp_path):
    (tmp_path / "broken.yaml").write_text("queries: [\n", encoding="utf-8")
    (tmp_path / "good.yaml").write_text(
        "queries:\n  - id: q\n    parameters: [a]\n", encoding="utf-8"
    )

    queries, notes = read_model_queries(tmp_path)
    assert queries == {"q": {"a"}}
    assert len(notes) == 1
    assert "broken.yaml" in notes[0]


# ── NLS-PACK-005 ─────────────────────────────────────────────────────────────


def a_query_pack(query: str, features: str) -> str:
    """A one-rule pack emitting a QueryPattern. Concatenated, not f-string'd —
    the YAML is nothing but flow mappings and every brace would need doubling."""
    return (
        "pack: qp\nversion: 1\nphases:\n"
        "  - phase: p\n    input: [Token]\n    control: appelt\n"
        "    rules:\n      - rule: R\n"
        "        lhs: [ { ann: Token, bind: b } ]\n"
        "        rhs: [ { add: { type: QueryPattern, features: { query: "
        + query
        + ", "
        + features
        + " } } } ]\n"
    )


def test_the_hero_packs_pass_the_model_cross_check():
    """The fixture model declares exactly what the heroes emit — the whole point
    of the lane is that a real pack and a real model agree."""
    diagnostics = validate_sources(
        [str(VALID_PACKS / "hero-cs-invoices.pack.yaml")], model=str(MODEL)
    )
    assert diagnostics == []


def test_an_unknown_query_id_is_reported(tmp_path):
    tree(
        tmp_path,
        {"q.pack.yaml": a_query_pack("nope", "x: { from: b, get: '@string' }")},
    )

    (diagnostic,) = validate_sources([str(tmp_path)], model=str(MODEL))
    assert diagnostic.code == NLS_PACK_005
    assert "`query: nope`" in diagnostic.message
    # Names what the model does declare — otherwise the author has to go read it.
    assert "faktury_zakaznika" in diagnostic.message


def test_a_misspelled_parameter_name_is_reported(tmp_path):
    """The failure this lane exists for. `nazev_zakaznik` for
    `nazev_zakaznika` produces a QueryPattern the typed-parameter rail reads and
    finds nothing in: the query runs, with the parameter empty. Nothing short of
    having the model in front of you catches it."""
    tree(
        tmp_path,
        {
            "q.pack.yaml": a_query_pack(
                "faktury_zakaznika", "nazev_zakaznik: { from: b, get: '@string' }"
            )
        },
    )

    (diagnostic,) = validate_sources([str(tmp_path)], model=str(MODEL))
    assert diagnostic.code == NLS_PACK_005
    assert "nazev_zakaznik" in diagnostic.message
    assert "nazev_zakaznika" in diagnostic.message


def test_a_correct_parameter_name_passes(tmp_path):
    tree(
        tmp_path,
        {
            "q.pack.yaml": a_query_pack(
                "faktury_zakaznika", "nazev_zakaznika: { from: b, get: '@string' }"
            )
        },
    )
    assert validate_sources([str(tmp_path)], model=str(MODEL)) == []


def test_a_model_with_no_queries_says_so_rather_than_condemning_every_pack(
    tmp_path,
):
    """Pointing `--model` at the wrong directory would otherwise report every
    query id in the pack tree as unknown — a wall of errors whose real cause is
    one wrong argument."""
    model = tmp_path / "empty"
    model.mkdir()
    diagnostics = validate_sources([str(VALID_PACKS)], model=str(model))

    assert len(diagnostics) == 1
    assert "no queries found" in diagnostics[0].message


def test_the_model_lane_is_off_unless_asked_for(tmp_path):
    tree(tmp_path, {"q.pack.yaml": a_query_pack("nope", "x: 1")})
    assert validate_sources([str(tmp_path)]) == []


def test_only_query_pattern_annotations_are_model_checked(tmp_path):
    """A pack's other `add:` types are its own vocabulary — `NameCandidate` in the
    invoices hero, for instance. contracts §5 gives a query contract to
    QueryPattern and to nothing else."""
    tree(
        tmp_path,
        {
            "q.pack.yaml": (
                "pack: other\nversion: 1\nphases:\n"
                "  - phase: p\n    input: [Token]\n    control: appelt\n"
                "    rules:\n      - rule: R\n"
                "        lhs: [ { ann: Token } ]\n"
                "        rhs: [ { add: { type: NameCandidate, features: "
                "{ query: not_a_real_query, whatever: 1 } } } ]\n"
            )
        },
    )
    assert validate_sources([str(tmp_path)], model=str(MODEL)) == []


def test_a_broken_pack_is_not_also_model_checked(tmp_path):
    """It never compiled, so there is nothing to cross-check — and adding a second
    complaint about a file that already failed helps nobody."""
    tree(tmp_path, {"bad.pack.yaml": good_pack("x").replace("appelt", "appelts")})

    diagnostics = validate_sources([str(tmp_path)], model=str(MODEL))
    assert {d.code for d in diagnostics} == {"NLS-PACK-001"}


def test_lists_are_untouched_by_the_model_lane(tmp_path):
    tree(tmp_path, {"l.list.yaml": good_list("vocab")})
    assert validate_sources([str(tmp_path)], model=str(MODEL)) == []
