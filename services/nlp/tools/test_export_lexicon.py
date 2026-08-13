# SPDX-License-Identifier: Apache-2.0
"""NLS-P4.T1 — the lexicon exporter, and the round trip that makes it trustworthy.

The load-bearing test here is `test_the_output_passes_the_suites_own_validator`:
an exporter whose output the suite then rejects is worse than no exporter, because
the failure lands on whoever mounts the lists rather than on whoever ran the
export. So the generated files go straight back through
`ttrnlp.packs.validate.validate_sources` — the same code path the service boots
with — and every entry must survive.

The fixture is a real slice of `tatrman/packages/kotlin/ttr-lexicon`'s sample
lexicon area, not a shape invented here: the point of the exporter is that it
reads what a defining repo actually writes.

The second thing under test is the one place the mapping is lossy. `TYPOS(n)` has
no gazetteer equivalent and must not silently acquire one, so the tests assert
both halves — the term IS exported (deterministically, as written) and the loss IS
reported. A silent downgrade would make the lists look like they covered what the
fuzzy path covers.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml
from ttrnlp.gazetteer import build_gazetteer, load_list
from ttrnlp.packs.validate import validate_sources

from .export_lexicon import (
    export_lexicon,
    features_for,
    list_id,
    main,
)

LEXICON = Path(__file__).resolve().parent.parent / "tests" / "fixtures" / "lexicon"


@pytest.fixture
def exported(tmp_path):
    """Run the exporter once; hand back the output dir and its notes."""
    out = tmp_path / "lists"
    written, notes = export_lexicon(LEXICON, out, origin="lexicon@fixture")
    return out, written, notes


def loaded(out: Path) -> dict[str, dict]:
    return {
        path.stem.removesuffix(".list"): yaml.safe_load(
            path.read_text(encoding="utf-8")
        )
        for path in sorted(out.glob("*.list.yaml"))
    }


# ── the round trip ───────────────────────────────────────────────────────────


def test_the_output_passes_the_suites_own_validator(exported):
    """The whole point. An exporter whose output the loader rejects moves the
    failure from the person who ran the export to the person who mounted it."""
    out, written, _ = exported
    assert written
    assert validate_sources([str(out)]) == []


def test_the_output_actually_annotates(exported):
    """Valid is not the same as useful: a list that loads and matches nothing
    would pass the validator and be worthless."""
    out, _, _ = exported
    from gatenlp import Document

    doc = Document("zákazník")
    doc.annset("").add(0, 8, "Token", {"text": "zákazník"})

    lists = [load_list(p) for p in sorted(out.glob("*.list.yaml"))]
    added = build_gazetteer(lists).annotate(doc)

    assert added == 1
    (lookup,) = doc.annset("").with_type("Lookup")
    assert lookup.features["entity"] == "Customer"
    assert lookup.features["kind"] == "entity_alias"
    # Provenance, per contracts §4.
    assert lookup.features["source"] == "lexicon-cs-ci"
    assert lookup.features["matching"] == "ci"


# ── the mode mapping ─────────────────────────────────────────────────────────


def test_one_list_per_language_and_mode(exported):
    out, _, _ = exported
    assert set(loaded(out)) == {
        "lexicon-cs-ci",
        "lexicon-cs-exact",
        "lexicon-en-ci",
    }


def test_exact_maps_to_exact(exported):
    out, _, _ = exported
    exact = loaded(out)["lexicon-cs-exact"]
    assert exact["matching"] == "exact"
    assert [e["term"] for e in exact["entries"]] == ["faktura"]


def test_tokens_maps_to_ci(exported):
    out, _, _ = exported
    terms = [e["term"] for e in loaded(out)["lexicon-cs-ci"]["entries"]]
    assert "tržba" in terms  # authored `method: TOKENS`


def test_typos_is_exported_as_written_and_the_loss_is_reported(exported):
    """Both halves. The term survives — the deterministic subset of what TYPOS
    covers is the term itself — and the dropped tolerance is said out loud."""
    out, _, notes = exported

    terms = [e["term"] for e in loaded(out)["lexicon-cs-ci"]["entries"]]
    assert "zákazník" in terms  # authored `TYPOS(1)` via `defaults`

    typo_notes = [n for n in notes if "TYPOS(1)" in n.message]
    assert typo_notes, "a dropped tolerance must never be silent"
    assert all(n.level == "INFO" for n in typo_notes)
    assert "lex-matcher" in typo_notes[0].message  # says where it went


def test_the_generated_file_says_so_in_its_header(exported):
    """The note reaches the person reading the list, not only the person who ran
    the export — those are rarely the same person or the same week."""
    out, _, _ = exported
    text = (out / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8")

    assert "GENERATED" in text
    assert "do not edit" in text
    assert "WITHOUT their tolerance" in text
    assert "NL-17" in text
    # And the `exact` list, which lost nothing, does not carry the warning.
    assert "WITHOUT their tolerance" not in (
        out / "lexicon-cs-exact.list.yaml"
    ).read_text(encoding="utf-8")


# ── kind derivation (RV-38: never authored) ──────────────────────────────────


@pytest.mark.parametrize(
    ("target", "expected"),
    [
        ("er.Customer", {"kind": "entity_alias", "entity": "Customer"}),
        (
            "md.measure.revenue",
            {"kind": "attribute_alias", "attribute": "revenue"},
        ),
        (
            "md.dimension.Account.class.expense",
            {"kind": "value_alias", "attribute": "Account", "value": "expense"},
        ),
        ("ground:money", {"kind": "keyword", "target": "ground:money"}),
    ],
)
def test_the_kind_comes_from_the_target(target, expected):
    assert features_for(target) == expected


def test_an_unrecognised_target_is_kept_as_a_keyword_not_dropped():
    """A term the exporter did not understand is still a term the analyst wrote
    down. The raw target rides along so nothing has to be guessed back."""
    features = features_for("something.entirely.new.here.and.longer")
    assert features["kind"] == "keyword"
    assert features["target"] == "something.entirely.new.here.and.longer"


def test_model_refs_survive_into_the_list(exported):
    out, _, _ = exported
    by_term = {
        e["term"]: e["features"] for e in loaded(out)["lexicon-cs-ci"]["entries"]
    }
    assert by_term["obchodní zástupce"] == {
        "kind": "value_alias",
        "attribute": "Role",
        "value": "obchodni_zastupce",
    }


# ── determinism ──────────────────────────────────────────────────────────────


def test_two_runs_produce_byte_identical_files(tmp_path):
    """Diffable in git is the requirement (T2). A re-run with no lexicon change
    must produce no diff, or every export becomes a review of itself."""
    first, second = tmp_path / "a", tmp_path / "b"
    export_lexicon(LEXICON, first, origin="lexicon@fixture")
    export_lexicon(LEXICON, second, origin="lexicon@fixture")

    for path in sorted(first.glob("*.list.yaml")):
        assert path.read_bytes() == (second / path.name).read_bytes()


def test_entries_are_sorted(exported):
    out, _, _ = exported
    for document in loaded(out).values():
        terms = [e["term"] for e in document["entries"]]
        assert terms == sorted(terms)


def test_a_duplicate_term_is_emitted_once():
    """Two lexicon files may name the same term for the same target; the list is
    a set, and a duplicate entry would double every Lookup it produced."""
    from .export_lexicon import Entry, Export, render_list

    export = Export()
    entry = Entry(term="x", features={"kind": "keyword"}, source_file="a")
    export.add("cs", "ci", entry)
    export.add("cs", "ci", Entry(term="x", features={"kind": "keyword"}, source_file="b"))

    document = yaml.safe_load(
        render_list("cs", "ci", export.lists[("cs", "ci")], origin="o")
    )
    assert [e["term"] for e in document["entries"]] == ["x"]


# ── ids, and the CLI ─────────────────────────────────────────────────────────


def test_a_bilingual_language_still_makes_a_legal_id():
    """`cs|en` is a legal lexicon lang and an illegal list id (`[a-z0-9-]+`)."""
    assert list_id("cs|en", "ci") == "lexicon-cs-en-ci"
    assert list_id("cs", "exact") == "lexicon-cs-exact"


def test_the_cli_writes_files_and_exits_zero(tmp_path, capsys):
    code = main([str(LEXICON), "-o", str(tmp_path), "--origin", "lexicon@x"])
    assert code == 0
    assert sorted(p.name for p in tmp_path.glob("*.list.yaml"))
    # The notes go to stderr so the stdout list of paths stays pipeable.
    captured = capsys.readouterr()
    assert "TYPOS(1)" in captured.err
    assert ".list.yaml" in captured.out


def test_a_missing_lexicon_directory_is_a_usage_error(tmp_path, capsys):
    assert main(["/nope/lexicon", "-o", str(tmp_path)]) == 2


def test_a_directory_with_no_lexicon_files_is_reported(tmp_path, capsys):
    """Not silence: an empty export usually means the wrong directory, and an
    exit 0 with no files would look like success."""
    empty = tmp_path / "empty"
    empty.mkdir()
    assert main([str(empty), "-o", str(tmp_path / "out")]) == 1
    assert "no ttr-lexicon/v1 files" in capsys.readouterr().err


def test_files_that_are_not_lexicon_files_are_ignored_not_rejected(tmp_path):
    """`lexicon-schemas.md` §1: notes may live beside a lexicon."""
    area = tmp_path / "lex"
    (area / "aliases").mkdir(parents=True)
    (area / "aliases" / "notes.md").write_text("just a note\n", encoding="utf-8")
    (area / "aliases" / "other.lex.yaml").write_text(
        "schema: something/else\nentries: []\n", encoding="utf-8"
    )
    (area / "aliases" / "real.lex.yaml").write_text(
        "schema: ttr-lexicon/v1\nentries:\n"
        "  - terms: [{text: x}]\n    target: er.Thing\n",
        encoding="utf-8",
    )

    _, notes = export_lexicon(area, tmp_path / "out", origin="o")
    assert [n for n in notes if n.level == "ERROR"] == []
    assert (tmp_path / "out" / "lexicon-cs-en-exact.list.yaml").exists()


# ── generation-expansion (NLS-P8.4 T1/T4, LM-7, C-O2) ────────────────────────
#
# `Kaufland` is a customer name — open vocabulary, so its paradigm lives in a
# WORLD morph layer (FI-1) rather than in the published core. The expanded list
# is how a pipeline that does NOT load the morph snapshot still matches
# *Kauflandu*: every form is a term of its own, matched `exact`.
#
# These tests import `ttrmorph`, which is a DEV dependency here and must stay
# one (⚑LMP-D4: it is not on PyPI). `test_the_exporter_still_works_without_it`
# is the half that keeps the exporter usable for a consumer who has only the
# wheel — which is most of them.

WORLD_LAYER = (
    "layer: world-dfp\nversion: 1\nlanguage: cs\nlicense: world:dfp\n"
    "attribution: null\nentries:\n"
    "  - { lemma: Kaufland, upos: PROPN, vzor: hrad, provenance: manual }\n"
    "  - { lemma: zákazník, upos: NOUN, vzor: pán, flags: [palatal],"
    " provenance: manual }\n"
    "  - { lemma: faktura, upos: NOUN, vzor: žena, provenance: manual }\n"
    "  - { lemma: tržba, upos: NOUN, vzor: žena, flags: [fleeting-e],"
    " provenance: manual }\n"
    "  - { lemma: středisko, upos: NOUN, vzor: město, provenance: manual }\n"
)


@pytest.fixture(scope="module")
def snapshot(tmp_path_factory):
    from ttrmorph.compile.snapshot import compile_layers

    directory = tmp_path_factory.mktemp("morph")
    layer = directory / "core.morph.yaml"
    layer.write_text(WORLD_LAYER.replace("world:dfp", "suite"), encoding="utf-8")
    result = compile_layers([str(layer)], snapshot_version="0.1.0")
    assert result.ok, [d.message for d in result.diagnostics]
    for name, text in result.outputs.items():
        (directory / name).write_text(text, encoding="utf-8")
    return directory / "cs.morph.snap"


@pytest.fixture
def morph_config(tmp_path, snapshot):
    path = tmp_path / "export-morph.yaml"
    path.write_text(
        f"snapshots: [{snapshot}]\ndefault: keep\n"
        "lists:\n  lexicon-cs-ci: expand\n  lexicon-cs-exact: lemma\n",
        encoding="utf-8",
    )
    return path


@pytest.fixture
def expanded(tmp_path, morph_config):
    from .export_lexicon import Morph

    out = tmp_path / "lists"
    written, notes = export_lexicon(
        LEXICON, out, origin="lexicon@fixture", morph=Morph.load(morph_config)
    )
    return out, written, notes


def test_an_entity_expands_to_every_generated_form(expanded):
    """The whole point: one authored term, every form a Czech sentence can
    contain, in a list that needs no runtime morphology."""
    terms = {
        entry["term"] for entry in loaded(expanded[0])["lexicon-cs-ci"]["entries"]
    }
    assert {"Kaufland", "Kauflandu", "Kauflandem", "Kauflandy", "Kauflandě"} <= terms


def test_each_generated_entry_carries_its_feats_and_its_lemma(expanded):
    by_term = {
        entry["term"]: entry["features"]
        for entry in loaded(expanded[0])["lexicon-cs-ci"]["entries"]
    }
    assert by_term["Kauflandu"]["lemma"] == "Kaufland"
    assert "Case=" in by_term["Kauflandu"]["feats"]
    # ...and the model ref the exporter derived is still on every one of them.
    assert by_term["Kauflandu"]["value"] == "kaufland"


def test_an_expanded_list_matches_exact(expanded):
    assert loaded(expanded[0])["lexicon-cs-ci"]["matching"] == "exact"


def test_a_list_left_to_the_runtime_matches_on_the_lemma(expanded):
    """The other half of the decision: same vocabulary, no expansion, and the
    morph layer declines the tokens instead (LM-8)."""
    document = loaded(expanded[0])["lexicon-cs-exact"]
    assert document["matching"] == "lemma"
    assert {entry["term"] for entry in document["entries"]} == {"faktura"}


def test_the_expanded_output_still_passes_the_suites_own_validator(expanded):
    """The round trip, again, on the bigger file. An exporter whose output the
    suite rejects fails on whoever mounts the lists, not on whoever ran it."""
    out, _, _ = expanded
    diagnostics = validate_sources([str(out)])
    assert not [d for d in diagnostics if d.severity == "error"], diagnostics


def test_the_expanded_list_annotates_a_DECLINED_form_with_no_morphology(expanded):
    """The claim the whole feature rests on, tested end to end: *Kauflandu* is
    a genitive nobody wrote down, and a gazetteer with no morph snapshot behind
    it now matches it — because the form is in the list."""
    out, _, _ = expanded
    from gatenlp import Document

    doc = Document("Kauflandu")
    doc.annset("").add(0, 9, "Token", {"text": "Kauflandu"})

    added = build_gazetteer([load_list(out / "lexicon-cs-ci.list.yaml")]).annotate(doc)
    assert added == 1
    (lookup,) = doc.annset("").with_type("Lookup")
    assert lookup.features["kind"] == "value_alias"
    assert lookup.features["value"] == "kaufland"
    assert lookup.features["lemma"] == "Kaufland"
    assert lookup.features["matching"] == "exact"


def test_a_term_the_snapshot_cannot_analyse_survives_and_is_reported(expanded):
    """*nákladové účty* is a phrase and *cost center* is English: neither is
    expanded, both still match as written, and the run says so."""
    out, _, notes = expanded
    terms = {
        entry["term"] for entry in loaded(out)["lexicon-cs-ci"]["entries"]
    }
    assert "nákladové účty" in terms
    assert any("multi-token" in str(note) for note in notes)


def test_the_generated_file_explains_its_own_size(expanded):
    out, _, _ = expanded
    text = (out / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8")
    assert "MORPH: generation-expanded" in text
    assert "NO runtime morphology" in text


def test_two_expanded_runs_produce_byte_identical_files(tmp_path, morph_config):
    from .export_lexicon import Morph

    first, second = tmp_path / "a", tmp_path / "b"
    for out in (first, second):
        export_lexicon(
            LEXICON, out, origin="lexicon@fixture", morph=Morph.load(morph_config)
        )
    for path in sorted(first.glob("*.list.yaml")):
        assert path.read_bytes() == (second / path.name).read_bytes()


def test_the_exporter_still_works_without_the_morph_toolchain(tmp_path):
    """No `--morph`, no import, no change. `ttr-morph` is not on PyPI, and this
    exporter has to keep working in a model repo that has only the wheel."""
    out = tmp_path / "lists"
    written, _ = export_lexicon(LEXICON, out, origin="lexicon@fixture")
    assert loaded(out)["lexicon-cs-ci"]["matching"] == "ci"
    assert {entry["term"] for entry in loaded(out)["lexicon-cs-ci"]["entries"]} == {
        "Kaufland",
        "nákladové účty",
        "obchodní zástupce",
        "středisko",
        "tržba",
        "zákazník",
    }


def test_the_cli_takes_the_morph_config(tmp_path, morph_config, capsys):
    out = tmp_path / "lists"
    code = main([str(LEXICON), "-o", str(out), "--morph", str(morph_config)])
    assert code == 0
    assert "Kauflandu" in (out / "lexicon-cs-ci.list.yaml").read_text(encoding="utf-8")
