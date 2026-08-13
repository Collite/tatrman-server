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
    --snapshot-version 0.1.0 --freq lexicon/cs/cac-freq.tsv
uv run ttr-morph compile world.morph.yaml -o dfp.morph.overlay \
    --overlay --world dfp

uv run ttr-morph eval --snapshot dist/cs.morph.snap                 # cases + targets
uv run ttr-morph eval --snapshot dist/cs.morph.snap --cac <UD-dir>  # + the metrics
uv run ttr-morph expand-lists <lists-dir> --config export-morph.yaml \
    --snapshot dist/cs.morph.snap
```

⚑ **Never `lexicon/cs/*.morph.yaml` in anger.** The order of a compile is the
precedence order, and a glob has no opinion about order — `lexicon/cs/LAYERS`
does. `just morph-compile` and the publish lane both read it; read its header
before changing what it says.

Or, from the repo root:

```
just morph-validate
just morph-compile 0.1.0
just morph-eval                      # the named cases + target coverage
just morph-eval <UD-dir>             # + the contracts §11 corpus metrics
just morph-eval "" gate              # what the morph/v* lane runs
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

## The frozen split — the ceremony, and why it only happens once

> **UD_Czech-CAC (CC BY-SA 4.0) is the SOLE eval oracle.** `ttr-morph split
> --seed 20260811` partitions CAC sentence ids into train/dev/test (80/10/10)
> **once**; the split manifest (ids + seed + CAC version + sha256) is committed
> at `shared/libs/python/ttr-morph/eval/cac-split.json` **before any CAC-derived
> seeding runs**, and the Wave C LM-6 training task **MUST train on the train
> side of this same manifest and evaluate on its test side — no re-split,
> ever.** Seeding/frequency extraction reads the train side only.
>
> — LM contracts §11, verbatim

The manifest is at `eval/cac-split.json`: UD_Czech-CAC r2.18, seed 20260811,
24,709 sentences split 19,767 / 2,471 / 2,471. It was committed **alone**, in a
commit that says so, and `tests/eval/test_frozen_manifest.py` pins its sha256 to
a literal — so if that test ever fails the fix is `git checkout`, not a new
literal.

**The rule is enforced in the reader, not in its callers.** `ttr-morph split`
refuses to overwrite an existing manifest; `importers.cac.sentences` refuses a
sentence id outside the side it was asked for, refuses to run at all if the
manifest does not exist, and refuses the TEST side unless the caller passes
`allow_test=True` **in its own source** — which the eval harness does and
nothing else may. `tests/test_test_side_guard.py` asserts that the flag appears
in exactly one module. A caller can be written wrong once per caller; a reader
can only be written wrong once.

Why our own partition when UD ships train/dev/test files: those are a split for
*parsing* benchmarks, drawn over documents whose composition we do not control
and which changes between releases. A frozen manifest of sentence ids is
reproducible from nothing but this file, and it pins the release it came from by
sha256 — so "the corpus changed under us" is detectable rather than assumed away.

## Eval — what the numbers mean

Four metrics (contracts §11), and the denominators are the argument:

| metric | denominator |
|---|---|
| coverage | **every** token — how much of running Czech the artifact knows |
| lemma-in-set accuracy | **answered** tokens — the gold lemma is somewhere in the set |
| head-of-list accuracy | **answered** tokens — the gold lemma is *first* |
| fold-collision rate | **answered** tokens — the fold index produced two lemmas |

Coverage is over every token because the honest answer in v1 is "the query
domain, not the language" (FI-1/GI-1). The accuracies are over answered tokens
because a token with no entry has no head of list to be wrong about — both are
printed with the all-token denominator beside them so nobody has to take that on
trust.

**The coverage gap is the Wave C headroom, not a defect.** Every run uses the
lexicon leg alone: the chain's statistical seam exists, defaults to `None`, and
nothing in v1 passes one. The uncovered-forms table at the end of
`eval/eval-report.md` is the brief for that work.

**Two runs, and only one needs the corpus.** `--cac` produces the full
measurement; `--gate` — what the `morph/v*` lane runs — checks the three S-7
named acceptance cases, the target-vocabulary coverage, and the artifact against
the committed `eval/baseline.json`, and reads no corpus at all. A release gate
that downloaded the oracle on every tag would be reading the test side unwatched,
on a schedule, forever.

`eval/cases/*.case.yaml` are the acceptance criteria themselves (S-7 → NLS arc
gate 7): the hero with its diacritics, the hero without them, and the NL hero's
lemma path through the core lexicon — which is the NC-free proof. A case may
expect a token to be **absent**, and one does: `Kaufland` is world vocabulary
(FI-1), and a core artifact that answered for it would be a core artifact that
had absorbed somebody's customer list.

### Two UD conventions the scorer carries

CAC collapses number in the lemma of a personal pronoun or a possessive — `já`
and `ty` and `on`, never `my`/`vy`/`oni`; `můj` and `tvůj` and `jeho`, never
`náš`/`váš`/`jejich`. We keep the citation form, because `lemma` is what a rule
pack matches on and an analyst writing a rule for "we" would never write `já`.
Neither side changes: the **scorer** carries it, on the gold side only, as a
declared equivalence in `eval/metrics.py`, and every run prints how many tokens
it scored that way.

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
- **Cell-scoped alternations.** `palatal` is a *stem*-level flag applied to every
  cell whose ending triggers it, and two live gaps need an alternation scoped to
  one cell instead: the `-ec` vocative (*otče*, while the locative is *otci*,
  same stem) and the imperative of `-it` verbs (*koupit* → *kup*, while the
  present is *koupím*). The second is most of the verbs the engine does not
  reproduce. A design decision, not an import fix.
- **Verb classes the tables do not have at all** — `nést`/`brát`/`mazat`/
  `sázet`/`umřít`. Six patterns cover a lot of Czech and not all of it.
- **Adverbs derived from adjectives** (*dobře*, *rychle*, *stále*) often rank
  under the adjective they came from, where CAC lemmatizes them to themselves.
  Roughly one point of overall head-of-list accuracy, and an editorial fix
  (separate ADV entries) rather than an engine one — the studio's queue.
- **Suppletive plurals.** CAC lemmatizes *let*/*letech* to `rok`; we say `léto`.
  Both are defensible readings of the form and only one is the oracle's.
