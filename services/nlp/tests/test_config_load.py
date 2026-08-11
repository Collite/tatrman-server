# SPDX-License-Identifier: Apache-2.0
"""RG-P1 phase-review regression — `load_config` finds the real `config.yaml`.

The default path must resolve to the service-root `config.yaml` (which pins the
model ids), not `src/config.yaml`. A wrong path silently dropped every backend
to an empty-model AppConfig() default outside the container (caught by the
phase-exit runtime smoke).
"""

from __future__ import annotations

from nlp_service.config import load_config


def test_default_load_config_reads_pinned_models():
    cfg = load_config()  # no CONFIG_FILE → default service-root path
    # The real config.yaml pins these; empty means the file wasn't found.
    assert cfg.engines.morphodita.model == "czech-morfflex2.0-pdtc1.0-220710"
    assert cfg.engines.nametag3.model == "nametag3-czech-cnec2.0-240830"
    # NLS-P3.2 moved the UFAL cs routing out of the base table and into the
    # `option` overlay: base IS the default lane now (contracts §7). The models
    # stay pinned either way — the lane decides whether they are routed, not
    # whether they are named.
    assert cfg.lane == "default"
    assert cfg.op_routing.get("LEMMATIZE.cs") == "stanza"
    assert cfg.lane_overrides["option"]["LEMMATIZE.cs"] == "morphodita"
    assert cfg.lane_overrides["option"]["NER.cs"] == "nametag3"


def test_ner_cs_is_absent_from_the_default_lane_entirely():
    """The NL-14 degrade, at the config layer. Not "routed to nothing" — absent,
    from both the base table and the resolved default-lane table."""
    cfg = load_config()
    assert "NER.cs" not in cfg.op_routing
    assert "NER.cs" not in cfg.resolved_op_routing()


def test_the_option_lane_restores_the_ufal_routing():
    cfg = load_config()
    cfg.lane = "option"
    resolved = cfg.resolved_op_routing()

    assert resolved["NER.cs"] == "nametag3"
    assert resolved["LEMMATIZE.cs"] == "morphodita"
    # The overlay does not disturb what it does not name.
    assert resolved["NER.en"] == "stanza"
    assert resolved["DEP_PARSE.cs"] == "stanza"


def test_the_ufal_engines_are_withheld_in_the_default_lane():
    """The half that makes "unrouted" real: routing's last-resort scan can only
    find engines that were registered, so the lane has to withhold them."""
    cfg = load_config()
    assert cfg.withheld_engines() == {"morphodita", "nametag3"}

    cfg.lane = "option"
    assert cfg.withheld_engines() == set()


def test_the_real_config_declares_pack_and_list_mount_points():
    cfg = load_config()
    assert cfg.packs.sources == ["/etc/nlp/packs"]
    assert cfg.lists.sources == ["/etc/nlp/lists"]
    # No pipelines by default: their ids belong to a world's packs, and a pipeline
    # referring to packs that are not mounted would fail the boot load.
    assert cfg.pipelines == {}


def test_explicit_config_file_still_honored():
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        f.write("default_language: en\nengines:\n  morphodita:\n    model: test-model\n")
        path = f.name
    cfg = load_config(path)
    assert cfg.default_language == "en"
    assert cfg.engines.morphodita.model == "test-model"


def test_nlp_lane_env_selects_the_lane(monkeypatch):
    """NLS-P3.2: the lane is a deployment decision, so the helm chart sets it as
    an env var rather than every cluster carrying a `config.yaml` that differs in
    one line."""
    monkeypatch.setenv("NLP_LANE", "option")
    assert load_config().lane == "option"


def test_a_typod_lane_keeps_the_safe_one(monkeypatch, caplog):
    """Not fatal, deliberately. A typo must not take the front down, and `default`
    is the lane that needs no licence decision — the safe one to be wrong
    towards."""
    monkeypatch.setenv("NLP_LANE", "optionn")
    with caplog.at_level("WARNING"):
        cfg = load_config()
    assert cfg.lane == "default"
    assert "NLP_LANE" in caplog.text


def test_the_lane_env_is_case_and_space_tolerant(monkeypatch):
    monkeypatch.setenv("NLP_LANE", "  OPTION ")
    assert load_config().lane == "option"
