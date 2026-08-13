# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.1 T1/T2 — the morph config block, and where its line is drawn.

The subject of this file is one decision: **what refuses to boot, and what comes
up saying so.**

A `morph: true` pipeline with no snapshot source declared anywhere can never run
on any cluster, and no amount of waiting fixes it — that is a typo, and it is a
boot error, exactly as an enabled `llm_emulated` with no URL is. A source that is
declared and unreadable is almost always a volume that has not mounted yet: the
front comes up, reports `LM-MORPH-001`, keeps serving everything that does not
need a lexicon, and starts working on the next `ReloadPacks`. Getting that line
wrong in either direction is expensive — a crash loop over a slow PVC, or a
silently morph-less deployment answering from stem guesses.

The rest is the swap: `steps()` is what `GetStatus` shows an operator, and it
has to name the two in-process steps rather than the engine ops they replaced.
"""

from __future__ import annotations

import pytest

from nlp_service.config import (
    MORPH_COVERED_OPS,
    STEP_MORPH_ANNOTATE,
    STEP_MORPH_TOKENIZE,
    AppConfig,
    MorphConfig,
    MorphQueueConfig,
    MorphWorldConfig,
    MorphConfigError,
    PipelineConfig,
    RuleRef,
    apply_env_overrides,
    validate_morph,
)

HERO = PipelineConfig(
    morph=True,
    ops=["NER"],
    gazetteer=["hero-aliases"],
    rules=[RuleRef(pack="hero-patterns", phase="query-match")],
)


def a_config(**morph) -> AppConfig:
    return AppConfig(
        morph=MorphConfig(**morph), pipelines={"query-patterns": HERO}
    )


# ── the swap, as GetStatus reports it ────────────────────────────────────────


def test_a_morph_pipeline_names_its_two_in_process_steps():
    assert HERO.steps() == [
        STEP_MORPH_TOKENIZE,
        STEP_MORPH_ANNOTATE,
        "NER",
        "hero-aliases",
        "hero-patterns:query-match",
    ]


def test_the_ops_morph_covers_drop_out_of_the_engine_list():
    spec = PipelineConfig(
        morph=True, ops=["TOKENIZE", "SENTENCE_SPLIT", "LEMMATIZE", "NER"]
    )
    assert spec.engine_ops() == ["SENTENCE_SPLIT", "NER"]
    assert spec.steps()[:4] == [
        STEP_MORPH_TOKENIZE,
        STEP_MORPH_ANNOTATE,
        "SENTENCE_SPLIT",
        "NER",
    ]


def test_sentence_split_and_pos_tag_are_NOT_covered():
    """The morph tokenizer does not split sentences and the annotator does not
    claim a part of speech it did not read from an entry. Claiming either would
    silently drop something a pack matches on."""
    assert MORPH_COVERED_OPS == {"TOKENIZE", "LEMMATIZE"}


def test_a_pipeline_without_the_flag_is_untouched():
    spec = PipelineConfig(ops=["TOKENIZE", "LEMMATIZE"], gazetteer=["x"])
    assert spec.steps() == ["TOKENIZE", "LEMMATIZE", "x"]


def test_the_flag_is_off_unless_a_pipeline_asks():
    assert PipelineConfig().morph is False


def test_an_unknown_pipeline_key_is_still_refused():
    """`extra=forbid` predates this and must survive it: a typo'd `morh: true`
    that loaded a pipeline with the engine path is the failure NL-15 exists to
    prevent."""
    with pytest.raises(ValueError):
        PipelineConfig(morh=True)


# ── what refuses to boot ─────────────────────────────────────────────────────


def test_a_morph_pipeline_with_no_sources_anywhere_refuses_to_boot():
    with pytest.raises(MorphConfigError, match="query-patterns"):
        validate_morph(a_config())


def test_a_declared_but_unreadable_source_is_NOT_a_boot_error(tmp_path):
    """The mount case. It is reported on GetStatus and comes good on reload —
    see `tests/pipeline/test_morph_pipeline.py` for the other half."""
    validate_morph(a_config(sources=[str(tmp_path / "not-mounted.snap")]))


def test_a_world_with_no_block_refuses_to_boot(tmp_path):
    with pytest.raises(MorphConfigError, match="none declared"):
        validate_morph(a_config(sources=[str(tmp_path / "s")], world="dfp"))


def test_a_world_id_that_is_not_filename_safe_refuses_to_boot(tmp_path):
    """The `dir:` sink spools one file per world, and `ReportToken.world` comes
    off the wire. A `../../etc/cron.d/x` in this table would turn a config typo
    into a path traversal with a helpful error message."""
    with pytest.raises(MorphConfigError, match="world id"):
        validate_morph(
            a_config(
                sources=[str(tmp_path / "s")],
                worlds={"../../etc/passwd": MorphWorldConfig()},
            )
        )


@pytest.mark.parametrize("sink", ["dir:", "url:", "somewhere-else", "file:/x"])
def test_a_sink_that_is_not_one_of_the_three_forms_refuses_to_boot(tmp_path, sink):
    with pytest.raises(MorphConfigError):
        validate_morph(
            a_config(
                sources=[str(tmp_path / "s")], queue=MorphQueueConfig(sink=sink)
            )
        )


def test_a_url_sink_with_no_spool_dir_warns_and_boots(tmp_path, caplog):
    validate_morph(
        a_config(
            sources=[str(tmp_path / "s")],
            world="dfp",
            worlds={"dfp": MorphWorldConfig()},
            queue=MorphQueueConfig(sink="url:http://morph-studio:8000"),
        )
    )
    assert any("spool_dir" in record.message for record in caplog.records)


def test_a_live_sink_with_no_world_at_all_refuses_to_boot(tmp_path):
    """⚑ The shape that boots green and learns nothing.

    `morph.world` unset is not "no world declared" — the check above only asks
    whether a *named* world has a block, and `""` names none. Everything else is
    wired: sources mounted, a `morph: true` pipeline, a live sink. And
    `SpoolSink.report("")` finds no policy, returns False, and `miss_sink`
    discards it — so the whole enrichment loop runs and collects nothing, which
    is the one failure nobody would go looking for.
    """
    with pytest.raises(MorphConfigError, match="morph.world"):
        validate_morph(
            a_config(
                sources=[str(tmp_path / "s")],
                worlds={"dfp": MorphWorldConfig()},
                queue=MorphQueueConfig(sink="dir:/var/lib/nlp/morph-queue"),
            )
        )


def test_no_world_is_fine_when_the_sink_is_off(tmp_path):
    """A front that reads the lexicon and feeds no studio is a real deployment
    — the rule above is about a queue with nowhere to put things, not about
    every morph pipeline needing a world."""
    validate_morph(
        a_config(sources=[str(tmp_path / "s")], queue=MorphQueueConfig(sink="none"))
    )


def test_a_deployment_with_no_morph_at_all_validates():
    """Every pre-LM config in the tree, and the shipped `config.yaml`."""
    validate_morph(AppConfig(pipelines={"engine-only": PipelineConfig()}))


def test_the_shipped_config_still_loads():
    from nlp_service.config import load_config

    config = load_config()
    assert config.morph.sources == []
    validate_morph(config)


# ── env overrides (a deployment decision, like the lane) ─────────────────────


def test_the_snapshot_path_the_world_and_the_sink_come_from_the_env(monkeypatch):
    monkeypatch.setenv("NLP_MORPH_SOURCES", "/etc/nlp/morph/cs.morph.snap, /o.overlay")
    monkeypatch.setenv("NLP_MORPH_WORLD", "dfp")
    monkeypatch.setenv("NLP_MORPH_QUEUE_SINK", "url:http://morph-studio:8000")
    monkeypatch.setenv("NLP_MORPH_QUEUE_SPOOL_DIR", "/var/lib/nlp/morph-queue")

    config = apply_env_overrides(AppConfig())
    assert config.morph.sources == ["/etc/nlp/morph/cs.morph.snap", "/o.overlay"]
    assert config.morph.world == "dfp"
    assert config.morph.queue.sink == "url:http://morph-studio:8000"
    assert config.morph.queue.spool_dir == "/var/lib/nlp/morph-queue"


def test_no_env_leaves_the_file_alone(monkeypatch):
    for name in ("SOURCES", "WORLD", "QUEUE_SINK", "QUEUE_SPOOL_DIR"):
        monkeypatch.delenv(f"NLP_MORPH_{name}", raising=False)
    config = apply_env_overrides(a_config(sources=["/from/file.snap"]))
    assert config.morph.sources == ["/from/file.snap"]
