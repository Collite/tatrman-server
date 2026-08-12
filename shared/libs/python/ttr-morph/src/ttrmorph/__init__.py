# SPDX-License-Identifier: Apache-2.0
"""``ttr-morph`` — the editorial side of the Tatrman morphology layer (LM).

The split between this package and the ``ttrnlp.morph`` wheel module is the
whole architecture in one line: **the wheel looks forms up, this package
decides what the forms are.** Nothing here is imported at query time. The
runtime reads a compiled snapshot and a hash map; a paradigm engine in the
serving path would be a per-token computation in exchange for a few megabytes
saved (design §3), and it would put the vzor tables — which change on an
editorial cadence — inside a service release.

So the two halves meet at exactly two artifacts:

* the **snapshot** (``*.morph.snap``, contracts §2) that this package compiles
  and the wheel loads, published on its own ``morph/v*`` tag lane;
* the **fold** (contracts §4), which is a *function*, and which this package
  imports from the wheel rather than reimplementing — see `ttrmorph.engine.fold`.

Sub-packages:

``engine``
    The paradigm generator and its inverse. Language-neutral driver, tables as
    data (LM-2): `generate` and `classify`.
``seed``
    The seeding lane (design §6) and, under ``data/``, the vzor tables
    themselves.
``importers``
    kaikki (Wiktionary) and UD_Czech-CAC readers, both classify-with-validation.
``compile``
    Layer files to snapshot.
``export``
    Generation-expanded gazetteer lists (LM-7, C-O2).
``eval``
    The frozen-split harness (LM-16/S-6).

Note on two module names: ``compile`` and ``eval`` shadow builtins *as
attribute names on this package*. That is deliberate — they are the words the
contracts use for these stages, and `ttrmorph.compile` never collides with the
builtin because the builtin is not reachable through a package attribute. Do
not write ``from ttrmorph import compile`` in code that also calls the builtin.
"""

__all__ = ["__version__"]

#: Kept at 0.0.0 in the tree. Unlike ttr-nlp there is no tag-injected build:
#: this package is not published (⚑LMP-D4). The version that matters to a
#: consumer is the snapshot's, which the snapshot header carries.
__version__ = "0.0.0"
