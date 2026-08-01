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
    assert cfg.op_routing.get("LEMMATIZE.cs") == "morphodita"
    assert cfg.op_routing.get("NER.cs") == "nametag3"


def test_explicit_config_file_still_honored():
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        f.write("default_language: en\nengines:\n  morphodita:\n    model: test-model\n")
        path = f.name
    cfg = load_config(path)
    assert cfg.default_language == "en"
    assert cfg.engines.morphodita.model == "test-model"
