# ttr-morph

The **editorial side** of the Tatrman morphology layer (LM): the Czech paradigm
engine, the lexicon importers, the snapshot compiler, the gazetteer exporters
and the eval harness.

Its counterpart is `ttrnlp.morph` in the [`ttr-nlp`](../ttr-nlp) wheel. The
split is the architecture in one line:

> **the wheel looks forms up; this package decides what the forms are.**

Nothing here runs at query time. The runtime reads a compiled snapshot and a
hash map — a paradigm engine in the serving path would be a per-token
computation in exchange for a few megabytes, and it would put the vzor tables,
which change on an editorial cadence, inside a service release.

## Not published

This package stays at version `0.0.0` and is **not** on PyPI (⚑LMP-D4). The
published artifact of this effort is the *snapshot* — `morph/v<x.y.z>`
(contracts §10) — plus, for world repos that must validate their own layer
files, the `nlp-morph-tools` image with this CLI baked in.

## The engine

```python
from ttrmorph.engine import generate, classify, fold

generate("tržba", "žena")            # -> {("tržby", "Case=Gen|Gender=Fem|Number=Sing"), ...}
classify({"Case=Nom|Gender=Fem|Number=Sing": "tržba", ...})   # -> ("žena", ())
```

`generate` is the direction the artifact is built from. `classify` is its
inverse, and it is what makes an importer trustworthy: instead of believing a
scraped inflection table, the importer asks which (vzor, flags) *reproduce* it
exactly, and a table no pattern reproduces becomes a full-form entry with a
diagnostic rather than a silently wrong compact one (D-F1-α). The same inverse
is the enrichment loop's validation primitive at NLS-P9 — a proposal is
auto-validated only when the form somebody actually typed is in the generated
paradigm.

`fold` is **re-exported from the wheel, never reimplemented.** The compiler
builds the snapshot's fold index and the runtime reads it; if the two folded
differently, every diacritics-less lookup would miss and nothing would fail
loudly enough to notice.

### All the Czech is in the tables

`src/ttrmorph/seed/data/cs/vzory.yaml` holds the patterns, the flags, the
sub-vzor inventory and the spelling rules. `src/ttrmorph/engine/` holds a
driver with no language in it, and a test asserts that literally — no Czech
letter may appear in the driver. The moment a rule about one language is easier
to write in the driver than in the tables, the tables stop being the
description of the language and "a second language is a second data file"
(LM-2) becomes false.

The driver implements six flag *kinds*; the flags themselves are parameters:

| kind | flag | what it covers |
|---|---|---|
| `stem-allomorph` | `fleeting-e` | pes/psa, píseň/písně, matka/matek — both directions of one alternation |
| `stem-vowel-map` | `shorten` | dům/domu, sníh/sněhu |
| `stem-final-map` | `palatal` | matka/matce, Praha/Praze, vlk/vlci |
| `stem-strip` | `foreign-stem` | cyklus/cyklu |
| `invariant` | `indeclinable` | atašé |
| `add-lemma` | `acronym` | ČEZ/ČEZu **and** "od ČEZ" |

## CLI

```
uv run ttr-morph generate tržba žena
uv run ttr-morph generate matka žena --flag palatal --flag fleeting-e
uv run ttr-morph classify some-table.yaml
uv run ttr-morph vzory
```

## Development

```
just lint-py shared/libs/python/ttr-morph
just test-py shared/libs/python/ttr-morph
```

`ttr-nlp` is wired as an editable path dependency: the two move together
through the whole LM arc, and a version pin would mean every wheel change needs
a release before this package can see it.

## Known gaps (deliberate, recorded here so they are not rediscovered)

- **Colloquial verb forms.** `kupuju`/`kupujou` are common in typed queries but
  absent from the tables: adding them would make every textbook table fail
  `classify`. They belong in the enrichment loop as full-form entries.
- **Soft-stem neuters of the `kuře` type** whose softness is carried by the
  citation `ě` (`kotě` → `koť-`): the strip cannot recover the soft consonant,
  so those need it spelled in the layer file. Not query-domain vocabulary.
- **`město`-type locatives after a velar** (`jablko` → `v jablku`, never
  `*jablce`) need an entry-level narrowing that does not exist yet.
- **The short adjectival plural** of `-ová` surnames ("sestry Novákovy") is not
  generated; `adj-ova` produces the long adjectival plural.
