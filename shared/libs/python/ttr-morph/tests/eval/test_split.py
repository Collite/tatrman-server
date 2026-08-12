# SPDX-License-Identifier: Apache-2.0
"""`ttrmorph.eval.split` — the partition function and the manifest reader.

The *committed* manifest is asserted in `test_frozen_manifest.py`, which was
written and committed together with it. This file is about the code: given ids
and a seed, what comes out, and what the reader refuses.
"""

from __future__ import annotations

import json

import pytest

from ttrmorph.eval.split import (
    CORPUS,
    FROZEN_SEED,
    SIDES,
    SplitError,
    build,
    load_manifest,
    partition,
    render,
    sentence_ids,
)

IDS = [f"doc{n // 100}-s{n % 100}" for n in range(1000)]


def synthetic(tmp_path, **overrides):
    """A manifest file that is not the frozen one — for the reader's negatives."""
    payload = {
        "corpus": CORPUS,
        "release": "r2.18",
        "sha256": "0" * 64,
        "seed": FROZEN_SEED,
        "counts": {"train": 2, "dev": 1, "test": 1},
        "train": ["a-s1", "a-s2"],
        "dev": ["a-s3"],
        "test": ["a-s4"],
        **overrides,
    }
    path = tmp_path / "cac-split.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


# ── partition ────────────────────────────────────────────────────────────────


def test_the_partition_is_deterministic():
    assert partition(IDS) == partition(IDS)


def test_the_partition_does_not_depend_on_input_order():
    """Ids are sorted before the shuffle, so the result is about the *set*."""
    assert partition(IDS) == partition(list(reversed(IDS)))


def test_the_ratios_are_eighty_ten_ten():
    sides = partition(IDS)
    total = sum(len(side) for side in sides.values())
    assert total == len(IDS)
    assert abs(len(sides["train"]) / total - 0.8) < 0.005
    assert abs(len(sides["dev"]) / total - 0.1) < 0.005
    assert abs(len(sides["test"]) / total - 0.1) < 0.005


def test_the_sides_are_disjoint_and_complete():
    sides = partition(IDS)
    seen = [i for side in SIDES for i in sides[side]]
    assert sorted(seen) == sorted(IDS)
    assert len(seen) == len(set(seen))


def test_a_different_seed_gives_a_different_split():
    assert partition(IDS, seed=1) != partition(IDS, seed=2)


def test_each_side_is_sorted():
    """A manifest whose ids were in shuffle order would hash differently from
    identical input."""
    for side in partition(IDS).values():
        assert side == sorted(side)


# ── reading the corpus ───────────────────────────────────────────────────────


def test_sentence_ids_reads_only_ids(tmp_path):
    path = tmp_path / "x.conllu"
    path.write_text(
        "# newdoc id = a\n# sent_id = a-s1\n# text = ...\n"
        "1\tKolektivní\tkolektivní\tADJ\t_\tCase=Nom\t3\tamod\t_\t_\n\n"
        "# sent_id = a-s2\n1\tzávazek\tzávazek\tNOUN\t_\tCase=Nom\t0\troot\t_\t_\n",
        encoding="utf-8",
    )
    assert sentence_ids([path]) == ["a-s1", "a-s2"]


def test_build_is_byte_stable(tmp_path):
    archive = tmp_path / "corpus.tar.gz"
    archive.write_bytes(b"not really an archive")
    conllu = tmp_path / "x.conllu"
    conllu.write_text("# sent_id = a-s1\n\n# sent_id = a-s2\n", encoding="utf-8")
    first = build([conllu], release="r2.18", archive=archive)
    second = build([conllu], release="r2.18", archive=archive)
    assert render(first) == render(second)
    assert json.loads(render(first))["seed"] == FROZEN_SEED


def test_building_from_nothing_is_refused(tmp_path):
    archive = tmp_path / "corpus.tar.gz"
    archive.write_bytes(b"x")
    empty = tmp_path / "empty.conllu"
    empty.write_text("", encoding="utf-8")
    with pytest.raises(SplitError):
        build([empty], release="r2.18", archive=archive)


# ── the reader's refusals ────────────────────────────────────────────────────


def test_a_missing_manifest_says_what_did_not_happen(tmp_path):
    with pytest.raises(SplitError) as caught:
        load_manifest(tmp_path / "nope.json")
    assert "before it exists" in str(caught.value)


def test_a_manifest_with_another_seed_is_refused(tmp_path):
    """A different seed is a different partition wearing the same filename."""
    with pytest.raises(SplitError) as caught:
        load_manifest(synthetic(tmp_path, seed=1))
    assert "not the shared split" in str(caught.value)


def test_a_manifest_for_another_corpus_is_refused(tmp_path):
    with pytest.raises(SplitError) as caught:
        load_manifest(synthetic(tmp_path, corpus="UD_Czech-PDT"))
    assert "sole eval oracle" in str(caught.value)


def test_a_truncated_manifest_is_refused(tmp_path):
    path = tmp_path / "cac-split.json"
    path.write_text(json.dumps({"corpus": CORPUS, "seed": FROZEN_SEED}), "utf-8")
    with pytest.raises(SplitError) as caught:
        load_manifest(path)
    assert "missing" in str(caught.value)


def test_a_manifest_that_is_not_json_is_refused(tmp_path):
    path = tmp_path / "cac-split.json"
    path.write_text("nope", encoding="utf-8")
    with pytest.raises(SplitError):
        load_manifest(path)


def test_an_unknown_side_is_refused(tmp_path):
    manifest = load_manifest(synthetic(tmp_path))
    with pytest.raises(SplitError):
        manifest.side("validation")
