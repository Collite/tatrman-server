# SPDX-License-Identifier: Apache-2.0
"""The CAC reader — and the guard that is the point of this whole list.

`test_the_reader_refuses_to_run_without_the_manifest` is the one that matters.
The rule is not "seeding should use the train side"; it is that nothing derived
from CAC may be read before the split is frozen, because the split is shared
with a training task that will be evaluated on the other half. A rule enforced
in the caller can be forgotten once per caller. This one is in the reader.
"""

from __future__ import annotations

import json

import pytest

from ttrmorph.eval.split import SplitError, SplitManifest, load_manifest
from ttrmorph.importers.cac import (
    attested_for,
    frequency_table,
    is_word,
    read,
    sentences,
)
from ttrmorph.importers.sources import PoisonedSource

SENTENCES = {
    "a-s1": [
        ("1", "tržby", "tržba", "NOUN", "_", "Case=Nom|Gender=Fem|Number=Plur"),
        ("2", "rostly", "růst", "VERB", "_", "Gender=Fem|Number=Plur|Tense=Past"),
        ("3", ".", ".", "PUNCT", "_", "_"),
    ],
    "a-s2": [
        ("1", "tržba", "tržba", "NOUN", "_", "Case=Nom|Gender=Fem|Number=Sing"),
    ],
    "a-s3": [
        ("1", "&camount;", "&camount;", "NOUN", "_", "Case=Nom|Number=Sing"),
        ("2", "korun", "koruna", "NOUN", "_", "Case=Gen|Gender=Fem|Number=Plur"),
    ],
}


@pytest.fixture
def corpus(tmp_path):
    lines = []
    for sent_id, rows in SENTENCES.items():
        lines.append(f"# sent_id = {sent_id}")
        lines.append("# text = ...")
        lines.extend("\t".join((*row, "0", "root", "_", "_")) for row in rows)
        lines.append("")
    path = tmp_path / "cs_cac-ud-train.conllu"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


@pytest.fixture
def manifest():
    return SplitManifest(
        corpus="UD_Czech-CAC",
        release="r2.18",
        sha256="0" * 64,
        seed=20260811,
        train=("a-s1", "a-s2"),
        dev=("a-s3",),
        test=(),
    )


# ── the guard ────────────────────────────────────────────────────────────────


def test_the_reader_refuses_to_run_without_the_manifest(corpus, monkeypatch):
    """⚠ The one that matters. Nothing CAC-derived before the freeze."""
    monkeypatch.setattr(
        "ttrmorph.importers.cac.load_manifest",
        lambda *a, **k: (_ for _ in ()).throw(SplitError("no manifest")),
    )
    with pytest.raises(SplitError):
        list(sentences([corpus]))


def test_the_real_manifest_is_what_the_reader_loads_by_default(corpus):
    """No argument needed and none accepted from the environment: the reader
    finds the committed manifest itself."""
    assert load_manifest().corpus == "UD_Czech-CAC"


def test_only_the_requested_side_is_yielded(corpus, manifest):
    seen = [sent_id for sent_id, _ in sentences([corpus], manifest=manifest)]
    assert seen == ["a-s1", "a-s2"]
    assert "a-s3" not in seen


def test_the_test_side_has_to_be_asked_for_by_name(corpus, manifest):
    assert list(sentences([corpus], side="dev", manifest=manifest)) != []
    assert list(sentences([corpus], side="test", manifest=manifest)) == []


def test_an_unknown_side_is_refused(corpus, manifest):
    with pytest.raises(SplitError):
        list(sentences([corpus], side="everything", manifest=manifest))


def test_a_poisoned_path_is_refused(tmp_path, manifest):
    path = tmp_path / "cs_pdt-ud-train.conllu"
    path.write_text("# sent_id = a-s1\n", encoding="utf-8")
    with pytest.raises(PoisonedSource):
        list(sentences([path], manifest=manifest))


# ── what it extracts ─────────────────────────────────────────────────────────


def test_punctuation_is_not_lexicon_material(corpus, manifest):
    counts, _ = read([corpus], manifest=manifest)
    assert not any(key[3] == "" and key[0] == "." for key in counts)
    assert ("tržby", "tržba", "NOUN", "Case=Nom|Gender=Fem|Number=Plur") in counts


def test_the_anonymisation_placeholders_are_not_words(corpus, manifest):
    """`&camount;` occurs 2,030 times in CAC and would head the frequency
    table — the most common noun in the language, and not a word."""
    assert not is_word("&camount;")
    assert is_word("tržba") and is_word("out-of-the-box")
    counts, _ = read([corpus], side="dev", manifest=manifest)
    assert not any("&" in key[1] for key in counts)


def test_the_report_counts_what_was_read(corpus, manifest):
    _, report = read([corpus], manifest=manifest)
    assert report.sentences_read == 2
    assert report.tokens == 4
    assert report.lemmas == 2


def test_the_frequency_table_is_lemma_then_count(corpus, manifest):
    counts, _ = read([corpus], manifest=manifest)
    lines = frequency_table(counts).splitlines()
    assert lines[0].split("\t") == ["tržba", "2"]
    assert all(len(line.split("\t")) == 2 for line in lines)


def test_attested_rows_group_by_lemma_and_upos(corpus, manifest):
    counts, _ = read([corpus], manifest=manifest)
    grouped = attested_for(counts, ["tržba"])
    assert set(grouped) == {("tržba", "NOUN")}
    assert {row.form for row in grouped[("tržba", "NOUN")]} == {"tržba", "tržby"}


def test_min_count_drops_the_once_seen(corpus, manifest):
    """A form seen once is evidence of a token, not of a word."""
    counts, _ = read([corpus], manifest=manifest)
    assert attested_for(counts, ["růst"], min_count=2) == {}


def test_nothing_reproduces_a_sentence(corpus, manifest):
    """The layer carries words, not text — the attribution says so and the
    shape has to match it."""
    counts, _ = read([corpus], manifest=manifest)
    payload = json.dumps(sorted(f"{k[0]} {k[1]}" for k in counts))
    assert "rostly tržby" not in payload
