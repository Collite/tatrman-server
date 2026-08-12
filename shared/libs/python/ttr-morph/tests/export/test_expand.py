# SPDX-License-Identifier: Apache-2.0
"""Generation-expansion of gazetteer lists (T1/T4, LM-7, C-O2).

Three properties carry this module and each has a test that would fail loudly if
it stopped holding:

*Nothing is lost.* An expanded list is a superset of the list it came from. A
term the lexicon cannot analyse, a phrase, a term whose paradigm the artifact
spells differently — all survive as written. An "improvement" that made an
analyst's own term stop matching would be the worst outcome available here.

*The forms come from the artifact.* Not from the engine. A full-form entry has
no vzor, and an engine-driven expander would emit nothing for it while reporting
success — so the expander loads the snapshot and inverts it, and what it emits
is what the runtime will actually see.

*The mode follows the decision.* `expand` means `matching: exact` and every
form; `lemma` means the terms as written and the runtime declines them. Both are
correct, they are correct in different deployments, and the file says which one
it is.
"""

from __future__ import annotations

import pytest
import yaml
from ttrnlp.gazetteer.lists import load_list
from ttrnlp.morph import load_morph

from ttrmorph.compile.snapshot import compile_layers
from ttrmorph.export.expand import (
    ExpandError,
    ExpandReport,
    MorphConfig,
    build_index,
    expand_document,
    expand_lists,
    header_for,
    lemma_of,
    read_config,
)

LAYER = (
    "layer: core-hand\nversion: 1\nlanguage: cs\nlicense: suite\n"
    "attribution: null\nentries:\n"
    "  - { lemma: tržba, upos: NOUN, vzor: žena, flags: [fleeting-e],"
    " provenance: manual }\n"
    "  - { lemma: zákazník, upos: NOUN, vzor: pán, flags: [palatal],"
    " provenance: manual }\n"
    "  - { lemma: Kaufland, upos: PROPN, vzor: hrad, provenance: manual }\n"
    "  - { lemma: středisko, upos: NOUN, provenance: manual,"
    ' forms: [{form: středisko, feats: "Case=Nom|Number=Sing"},'
    ' {form: střediska, feats: "Case=Gen|Number=Sing"}] }\n'
)


@pytest.fixture(scope="module")
def state(tmp_path_factory):
    directory = tmp_path_factory.mktemp("expand")
    path = directory / "core-hand.morph.yaml"
    path.write_text(LAYER, encoding="utf-8")
    result = compile_layers([str(path)], snapshot_version="0.1.0")
    assert result.ok, [d.message for d in result.diagnostics]
    for name, text in result.outputs.items():
        (directory / name).write_text(text, encoding="utf-8")
    return load_morph([str(directory / "cs.morph.snap")])


@pytest.fixture
def index(state):
    return build_index(state)


def document(*terms, list_id="lexicon-cs-ci", matching="ci"):
    return {
        "list": list_id,
        "version": 1,
        "matching": matching,
        "annotation": "Lookup",
        "source": {"world": "tatrman", "origin": "lexicon@fixture"},
        "entries": [
            {"term": term, "features": {"kind": "entity_alias"}} for term in terms
        ],
    }


def expand(doc, state, index, decision="expand"):
    return expand_document(doc, state, index, decision=decision)


def terms_of(doc):
    return {entry["term"] for entry in doc["entries"]}


# ── the index ────────────────────────────────────────────────────────────────


def test_the_index_holds_every_form_the_artifact_has(index):
    forms = {form for form, _ in index.forms_for("tržba")}
    assert {"tržba", "tržby", "tržbě", "tržbou", "tržeb", "tržbami"} <= forms


def test_a_full_form_entry_expands_too(index):
    """The reason expansion reads the artifact rather than re-running the
    engine: this entry has no vzor at all."""
    assert {form for form, _ in index.forms_for("středisko")} == {
        "středisko",
        "střediska",
    }


def test_the_index_carries_per_form_feats(index):
    feats = dict(index.forms_for("středisko"))
    assert feats["střediska"] == "Case=Gen|Number=Sing"


def test_a_term_is_resolved_the_way_the_runtime_would(state):
    assert lemma_of("Kauflandu", state) == "Kaufland"
    assert lemma_of("nothing-here", state) is None


# ── expansion ────────────────────────────────────────────────────────────────


def test_an_entity_expands_to_all_its_forms(state, index):
    expanded, report = expand(document("Kaufland"), state, index)
    assert {"Kaufland", "Kauflandu", "Kauflandem", "Kauflandy"} <= terms_of(expanded)
    assert report.expanded_terms == 1
    assert report.emitted == 9  # every distinct form, and the term is one of them


def test_every_emitted_entry_keeps_the_original_features(state, index):
    expanded, _ = expand(document("Kaufland"), state, index)
    assert all(
        entry["features"]["kind"] == "entity_alias" for entry in expanded["entries"]
    )


def test_every_emitted_entry_carries_its_lemma_and_feats(state, index):
    expanded, _ = expand(document("Kaufland"), state, index)
    by_term = {entry["term"]: entry["features"] for entry in expanded["entries"]}
    assert by_term["Kauflandu"]["lemma"] == "Kaufland"
    assert "Case=" in by_term["Kauflandu"]["feats"]


def test_expansion_drops_the_mode_to_exact(state, index):
    expanded, _ = expand(document("Kaufland"), state, index)
    assert expanded["matching"] == "exact"


def test_the_lemma_decision_leaves_the_terms_alone(state, index):
    expanded, report = expand(document("Kaufland"), state, index, decision="lemma")
    assert terms_of(expanded) == {"Kaufland"}
    assert expanded["matching"] == "lemma"
    assert report.changed


def test_keep_changes_nothing(state, index):
    doc = document("Kaufland")
    expanded, report = expand(doc, state, index, decision="keep")
    assert expanded == doc
    assert not report.changed


# ── nothing is lost ──────────────────────────────────────────────────────────


def test_a_term_the_lexicon_does_not_know_survives_and_is_counted(state, index):
    expanded, report = expand(document("Zoot"), state, index)
    assert "Zoot" in terms_of(expanded)
    assert report.unknown == ["Zoot"]


def test_a_multi_token_term_is_left_alone_and_counted(state, index):
    """A phrase declines in agreement; the cross product of two paradigms emits
    *obchodním zástupce*, which is not Czech."""
    expanded, report = expand(document("obchodní zástupce"), state, index)
    assert "obchodní zástupce" in terms_of(expanded)
    assert report.multiword == ["obchodní zástupce"]


def test_the_authored_term_survives_even_when_it_is_not_one_of_its_own_forms(
    state, index
):
    """*zakaznik* resolves by folding and is not in the paradigm it resolves to.
    Dropping it would mean an analyst's own term stopped matching after an
    export ran."""
    expanded, _ = expand(document("zakaznik"), state, index)
    assert "zakaznik" in terms_of(expanded)
    assert "zákazníka" in terms_of(expanded)


def test_expansion_is_deterministic(state, index):
    first, _ = expand(document("Kaufland", "tržba"), state, index)
    second, _ = expand(document("tržba", "Kaufland"), state, index)
    assert first["entries"] == second["entries"]


def test_two_terms_that_share_a_form_emit_it_once(state, index):
    expanded, _ = expand(document("tržba", "tržby"), state, index)
    assert len(terms_of(expanded)) == len(expanded["entries"])


# ── the output is still a gazetteer list ─────────────────────────────────────


def test_the_expanded_list_still_validates(state, index, tmp_path):
    """The round trip that makes the whole thing safe: the suite's own reader
    has to accept what the expander wrote."""
    expanded, report = expand(document("Kaufland"), state, index)
    path = tmp_path / "lexicon-cs-ci.list.yaml"
    path.write_text(
        yaml.safe_dump(expanded, allow_unicode=True, sort_keys=False), encoding="utf-8"
    )
    loaded = load_list(path)
    assert loaded.matching == "exact"
    assert len(loaded.entries) == report.emitted


def test_the_header_explains_the_size_to_a_human():
    report = ExpandReport(
        list_id="x", decision="expand", terms=1, expanded_terms=1, emitted=14
    )
    text = header_for(report)
    assert "14 entries" in text and "NO runtime morphology" in text


def test_the_header_names_what_was_not_expanded():
    report = ExpandReport(
        list_id="x", decision="expand", terms=2, unknown=["Zoot"], multiword=["a b"]
    )
    text = header_for(report)
    assert "Zoot" in text and "multi-token" in text


def test_the_lemma_header_says_it_needs_the_snapshot():
    assert "needs the cs morph snapshot" in header_for(
        ExpandReport(list_id="x", decision="lemma")
    )


# ── the config ───────────────────────────────────────────────────────────────


def test_the_config_decides_per_list(tmp_path):
    path = tmp_path / "morph.yaml"
    path.write_text(
        "snapshots: [dist/cs.morph.snap]\ndefault: lemma\n"
        "lists:\n  lexicon-cs-ci: expand\n",
        encoding="utf-8",
    )
    config = read_config(path)
    assert config.decision("lexicon-cs-ci") == "expand"
    assert config.decision("anything-else") == "lemma"
    assert config.snapshots == ("dist/cs.morph.snap",)


def test_the_default_default_changes_nothing():
    assert MorphConfig().decision("whatever") == "keep"


@pytest.mark.parametrize(
    "body",
    ["lists:\n  a: explode\n", "default: explode\n", "lists: [a, b]\n", "- x\n"],
)
def test_a_config_that_cannot_be_obeyed_is_refused(tmp_path, body):
    path = tmp_path / "morph.yaml"
    path.write_text(body, encoding="utf-8")
    with pytest.raises(ExpandError):
        read_config(path)


# ── the directory post-processor ─────────────────────────────────────────────


@pytest.fixture
def lists_dir(tmp_path, state):
    directory = tmp_path / "lists"
    directory.mkdir()
    (directory / "lexicon-cs-ci.list.yaml").write_text(
        "# SPDX-License-Identifier: Apache-2.0\n"
        "# GENERATED by the exporter — do not edit.\n"
        + yaml.safe_dump(document("Kaufland"), allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    (directory / "lexicon-cs-exact.list.yaml").write_text(
        yaml.safe_dump(
            document("SKU-1", list_id="lexicon-cs-exact", matching="exact"),
            allow_unicode=True,
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    return directory


def snapshot_paths(state):
    return [source for source in [state.manifest.source] if source]


def test_the_post_processor_rewrites_only_what_the_config_names(lists_dir, state):
    config = MorphConfig(lists={"lexicon-cs-ci": "expand"})
    reports = expand_lists(lists_dir, config, snapshots=snapshot_paths(state))
    by_id = {report.list_id: report for report in reports}
    assert by_id["lexicon-cs-ci"].expanded_terms == 1
    assert by_id["lexicon-cs-exact"].decision == "keep"

    untouched = yaml.safe_load(
        (lists_dir / "lexicon-cs-exact.list.yaml").read_text(encoding="utf-8")
    )
    assert untouched["entries"] == [
        {"term": "SKU-1", "features": {"kind": "entity_alias"}}
    ]


def test_the_files_own_header_survives_the_rewrite(lists_dir, state):
    expand_lists(
        lists_dir,
        MorphConfig(lists={"lexicon-cs-ci": "expand"}),
        snapshots=snapshot_paths(state),
    )
    text = (lists_dir / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8")
    assert text.startswith("# SPDX-License-Identifier: Apache-2.0")
    assert "GENERATED by the exporter" in text
    assert "MORPH: generation-expanded" in text


def test_expanding_twice_is_a_no_op(lists_dir, state):
    config = MorphConfig(lists={"lexicon-cs-ci": "expand"})
    expand_lists(lists_dir, config, snapshots=snapshot_paths(state))
    once = (lists_dir / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8")
    expand_lists(lists_dir, config, snapshots=snapshot_paths(state))
    assert (lists_dir / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8") == once


def test_a_run_with_nothing_to_do_needs_no_snapshot(lists_dir):
    """`keep` everywhere must not require loading an artifact — otherwise C-O2's
    dispatch fails on a repo that has no morph lists yet."""
    reports = expand_lists(lists_dir, MorphConfig())
    assert all(report.decision == "keep" for report in reports)


def test_an_empty_directory_is_an_error(tmp_path):
    with pytest.raises(ExpandError):
        expand_lists(tmp_path, MorphConfig())


def test_expansion_without_a_snapshot_says_what_is_missing(lists_dir):
    with pytest.raises(ExpandError, match="compiled snapshot"):
        expand_lists(lists_dir, MorphConfig(lists={"lexicon-cs-ci": "expand"}))
