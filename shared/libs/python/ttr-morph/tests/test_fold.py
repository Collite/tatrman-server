# SPDX-License-Identifier: Apache-2.0
"""The fold is the wheel's function, not a copy of it (contracts §4).

This package compiles the snapshot's fold index; the wheel reads it. If the two
folded differently, every diacritics-less lookup would miss — and it would miss
*silently*, because a fold index that disagrees with the runtime still looks
like a perfectly well-formed index. There is no assertion that could catch that
at load time, so the guarantee has to be structural: one implementation,
imported twice.
"""

from __future__ import annotations

import ttrnlp.morph

from ttrmorph.engine import fold


def test_the_fold_is_the_wheels_object():
    assert fold is ttrnlp.morph.fold


def test_it_folds_what_the_hero_needs_it_to():
    assert fold("tržby") == fold("trzby")
    assert fold("Kauflandu") == fold("kauflandu")
    assert fold("loňský") == "lonsky"
