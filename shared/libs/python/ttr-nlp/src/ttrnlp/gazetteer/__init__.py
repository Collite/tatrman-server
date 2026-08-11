# SPDX-License-Identifier: Apache-2.0
"""Gazetteers and list interchange (NL-3, NL-17).

`lists.py` is the interchange reader (contracts §4) and `annotate.py` turns
lists into `Lookup` annotations — a token trie for `lemma`/`ci`/`fold-diacritics`
and a string trie for `exact`. Deterministic longest-match only; the scoring line
stays world-side.
"""

from ttrnlp.gazetteer.annotate import Gazetteer, build_gazetteer
from ttrnlp.gazetteer.lists import GazetteerList, load_list

__all__ = ["Gazetteer", "GazetteerList", "build_gazetteer", "load_list"]
