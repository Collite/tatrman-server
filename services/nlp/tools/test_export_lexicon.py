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
