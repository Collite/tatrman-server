# SPDX-License-Identifier: Apache-2.0
"""NLS-P9.2 T1 — the settings, and the one that refuses to boot."""

from __future__ import annotations

import pytest

from morph_studio.config import (
    DEFAULT_PORT,
    ENV_MODE,
    ENV_PROVISIONAL,
    ENV_VOCABULARY,
    ENV_WORLD,
    ConfigError,
    Settings,
)


def test_a_studio_with_no_world_refuses_to_boot():
    """LM-5: an instance that guessed would write one world's words into
    another's file, and there is no repair for that after the fact."""
    with pytest.raises(ConfigError, match="serves exactly one world"):
        Settings.from_env({})


def test_a_world_id_that_is_not_a_filename_refuses_to_boot():
    with pytest.raises(ConfigError, match="not a usable id"):
        Settings.from_env({ENV_WORLD: "../etc"})


def test_the_defaults_are_the_ones_a_laptop_wants():
    settings = Settings.from_env({ENV_WORLD: "dfp"})

    assert settings.port == DEFAULT_PORT
    assert settings.mode == "studio"
    assert settings.provisional is True
    assert settings.overlay_dir == "", "Q-7 needs a directory the front mounts"
    assert settings.front_target == ""


def test_the_dfp_lane_is_a_mode_and_an_unknown_one_refuses():
    assert Settings.from_env({ENV_WORLD: "dfp", ENV_MODE: "dfp"}).dfp is True
    with pytest.raises(ConfigError, match="the lanes are"):
        Settings.from_env({ENV_WORLD: "dfp", ENV_MODE: "files"})


def test_q7_can_be_turned_off_per_deployment():
    settings = Settings.from_env({ENV_WORLD: "dfp", ENV_PROVISIONAL: "false"})
    assert settings.provisional is False


def test_the_model_vocabulary_is_a_list_and_blanks_do_not_become_terms():
    settings = Settings.from_env(
        {ENV_WORLD: "dfp", ENV_VOCABULARY: "Kaufland, Microsoft , ,"}
    )
    assert settings.vocabulary == ("Kaufland", "Microsoft")
