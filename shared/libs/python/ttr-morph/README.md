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

## Layer files — how the lexicon is written

A **layer** is a YAML file of entries. It is the editorial truth; the snapshot
is generated from it and is nobody's source. That is the whole reason the
compact form exists: a reviewer diffing `vzor: žena` against `vzor: růže` is
reading one decision, and a reviewer diffing fourteen forms is reading fourteen
consequences of it.

```yaml
layer: core-hand            # id, [a-z0-9-]+; becomes the `layer` column
version: 1
language: cs
license: suite              # suite | CC-BY-SA-4.0 | world:<id>
attribution: null           # required (and complete) for share-alike layers
entries:
  - lemma: tržba            # the compact form: a pattern, expanded by the engine
    upos: NOUN
    vzor: žena
    flags: []               # fleeting-e | shorten | palatal | foreign-stem |
                            #   indeclinable | acronym | subvzor:<name>
    rank: 3                 # optional; the frequency table fills it otherwise
    provenance: manual      # wiktionary | cac | manual | llm

  - lemma: být              # the explicit form: irregulars, importer misses
    upos: AUX
    provenance: manual
    forms:
      - { form: jsem, feats: "Mood=Ind|Number=Sing|Person=1|Tense=Pres" }

  - lemma: abych
    upos: SCONJ
    provenance: manual
    parts: [aby, bych]      # decomposition (B-O4)
    forms: [{ form: abych, feats: "Mood=Cnd|Number=Sing|Person=1" }]

  - lemma: nemoc
    upos: NOUN
    vzor: kost
    ne_exception: true      # the runtime's ne-/nej- strip must not touch it
    provenance: manual
```

**Entry identity is `(lemma, upos)`**, and a later layer replaces an earlier
one whole — every form of the lexeme, not the cells it happened to list. Unknown
keys are refused. `provenance` is required on every entry: it is what the
licence boundary is enforced against.

Two keys are not in contracts §3 and are documented here because the compiler
needs what they carry. **`ne_exception`** marks a lexeme the runtime's ne-/nej-
strip must leave alone — *nemoc* is not *ne* + *moc*, and only the lexicon knows
that. **`provisional`** marks a Q-7 entry: an unverified generated entity name,
legal only in a world overlay, refused in a core compile with `LM-MORPH-003`.

### `vzor` and `forms` together

Legal, and it means "here is the pattern, and here are forms I claim it makes".
If the pattern regenerates them, the **pattern expands** and the forms were a
spot-check that passed. If it does not, the **forms win**, the pattern is
dropped from the row, and you get `LM-MORPH-005` — a vzor that does not
reproduce the forms is not that lexeme's vzor, and leaving it in the artifact
would tell the studio's *try-pattern* the wrong thing.

### Share-alike layers

A layer under `CC-BY-SA-4.0` may hold **only** `wiktionary`/`cac` entries and
must carry a complete `attribution:` block (source, url, license, license_url,
extracted, transformation). Both are `LM-MORPH-004` ERRORs, because the failure
mode is not a broken test — it is a licence audit months after the release.

Such a layer compiles into a **separable member file** of its own
(`core-kaikki.morph.part`) beside the suite-licensed body, with its own section
in `NOTICE-morph.md`. That is the C-F3 separability proof: an aggregate of
member files, each keeping its own licence, rather than one file of blended
provenance. All of them are core — the runtime takes the leading run of
snapshot files as one core and the rest as world overlays.

## CLI

```
uv run ttr-morph generate tržba žena
uv run ttr-morph generate matka žena --flag palatal --flag fleeting-e
uv run ttr-morph classify some-table.yaml
uv run ttr-morph vzory

uv run ttr-morph validate lexicon/cs/*.morph.yaml
uv run ttr-morph compile lexicon/cs/*.morph.yaml -o dist/cs.morph.snap \
    --snapshot-version 0.1.0 --freq eval/cac-freq.tsv
uv run ttr-morph compile world.morph.yaml -o dfp.morph.overlay \
    --overlay --world dfp
```

Or, from the repo root:

```
just morph-validate
just morph-compile 0.1.0
```

Exit codes: `0` the question had an answer / the files are valid · `1` the
answer is no, or the files carry errors · `2` the command could not be run as
asked. `--json` emits diagnostics in the same five-field shape as
`ttr-nlp validate`, so the DFP model-validator wraps both identically.

A world repo that has no Python checkout runs the same validator from the
image:

```
docker run --rm -v "$PWD:/work" ghcr.io/collite/nlp-morph-tools:latest \
    validate /work/entities.morph.yaml
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
