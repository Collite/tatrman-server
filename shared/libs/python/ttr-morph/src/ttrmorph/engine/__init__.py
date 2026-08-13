# SPDX-License-Identifier: Apache-2.0
"""The paradigm engine — a language-neutral driver over tables (LM-2).

Public surface, exactly as contracts §4 declares it::

    generate(lemma, vzor, flags, *, lang="cs") -> {(form, feats)}
    classify(table, *, lang="cs")              -> (vzor, flags) | None
    fold(s)                                    -> str

`fold` is **re-exported from the wheel, never reimplemented here.** The
compiler builds the snapshot's fold index and the runtime reads it; if the two
folded differently, every diacritics-less lookup would miss and nothing would
fail loudly enough to notice. One function, imported twice, is the only version
of that guarantee that cannot drift — which is why this package depends on
`ttr-nlp` rather than the other way round.

Nothing in this sub-package contains a Czech letter. That is a test
(`tests/engine/test_neutrality.py`), not an aspiration: the moment a rule about
one language is easier to write in the driver than in the tables, the tables
stop being the description of the language and the claim that a second language
is a second data file becomes false.
"""

from ttrnlp.morph import fold

from ttrmorph.engine.errors import BadFlag, BadLemma, EngineError, UnknownVzor
from ttrmorph.engine.paradigm import classify, generate
from ttrmorph.engine.tables import Slot, Tables, Vzor, load

__all__ = [
    "BadFlag",
    "BadLemma",
    "EngineError",
    "Slot",
    "Tables",
    "UnknownVzor",
    "Vzor",
    "classify",
    "fold",
    "generate",
    "load",
]
