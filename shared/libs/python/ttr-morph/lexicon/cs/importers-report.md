# Seeding report — NLS-P8.3

Written from the run that produced the layer files beside it. Reproduce with the
commands in each section; the numbers are the ones a re-run should print.

## The artifact

| | |
|---|--:|
| rows | 25,097 |
| distinct forms | 24,575 |
| fold collisions | 1,400 |
| entries (core-hand) | 198 — 64 compact, 134 full-form |
| entries (core-kaikki) | 2,415 — 1,879 compact, 536 full-form |
| entries (core-cac) | 569 — all full-form, by design |

⚑ **The compile order is `core-cac`, `core-kaikki`, `core-hand`** — weakest to
strongest, because the last layer wins. It is declared in `lexicon/cs/LAYERS`
(NLS-P8.4) and read by both `just morph-compile` and the publish lane; before
that it was a glob, which has no opinion about order and which also silently
missed the hand seed. `core-hand` is suite-licensed and lands in the main body;
the two share-alike layers each compile into a separable member file with its
own NOTICE section (C-F3).

**Four hand entries were added at NLS-P8.4**, each one found by the eval harness
rather than by review: `zobrazit` (the NL hero's first word, which arrived from
the corpus as a bare infinitive), `pololetí` (syncretic in nine cells, so one
attested form looked like a complete entry), and the conditional auxiliary
`by`/`bych`/`bys`/`bychom`/`byste` on `být` — the single commonest word the
artifact could not analyse, at 169 tokens of the test side. See
`eval/eval-report.md`.

## Target-list coverage

**147 of 149 (99%)** of the authored target vocabulary
(`seed/data/cs/target-words.yaml` — the analytical vocabulary of the query
domain plus every content word in the NLS heroes) has an entry.

Missing: **`kvartál`** (absent from the English Wiktionary and below the CAC
frequency floor) and **`největší`** (a superlative; the runtime resolves it by
stripping `nej-` and looking up `velký`, so it needs no entry of its own — it is
in the target list because the list names words, not entries).

The S-7 hero resolves end to end from the core artifact alone:

| form | lemma | how |
|---|---|---|
| Porovnej | porovnat | folded (the capital) |
| tržby | tržba | exact |
| Kauflandu | — | **a world entity; correctly absent from core** |
| za / rok / s | za / rok / s | exact |
| loňský / letošním | loňský / letošní | exact |
| trzby (the diacritics-less twin) | tržba | folded |

`Kaufland` is the enrichment loop's entry point, exactly as NLS-P7 left it.

## The kaikki import — and the engine's conformance number

```
ttr-morph import-kaikki cs-enwiktionary.jsonl -o lexicon/cs/core-kaikki.morph.yaml \
    --targets src/ttrmorph/seed/data/cs/target-words.yaml \
    --targets-from-freq lexicon/cs/cac-freq.tsv --top 3000 \
    --exclude lexicon/cs/core-hand.morph.yaml
```

| measure | value |
|---|--:|
| entries read | 72,049 |
| in the target list | 2,767 |
| classified to a pattern | 1,879 |
| full-form (no pattern reproduced) | 536 |
| no usable table | 352 |
| forms dropped (register / outside the subset / unmapped) | 7,306 |
| **reproduce rate** | **78%** |

This run **is** the engine conformance harness (D-F1-α): every classified entry
is a paradigm the engine reproduced exactly against an independently authored
table. 78% against D-O1's measured 60% for nouns, and the difference is the five
table fixes below.

By part of speech, on the same target set: NOUN 80%, VERB 54%, ADJ ~99% (the
adjective patterns are two and they cover the language), PROPN 7%.

Top patterns: `mladý` 461 · `žena` 292 · `hrad-u` 192 · `jarní` 181 ·
`stavení` 139 · `růže` 88 · `hrad` 88 · `dělat` 72.

Full-form entries by part of speech: NOUN 240 · VERB 166 · ADV 54 · PROPN 35 ·
PRON 26 · DET 10 · NUM 3. The verb residue is the honest number here — see the
gaps below.

## The CAC extraction

```
ttr-morph import-cac UD_Czech-CAC-r2.18 -o lexicon/cs/core-cac.morph.yaml \
    --freq lexicon/cs/cac-freq.tsv --min-count 2 \
    --exclude <hand> --exclude <kaikki>
```

19,767 train-side sentences (the frozen split's train side, and nothing else),
339,782 tokens, 25,940 lemmas in the frequency table. 569 entries for
target-vocabulary lemmas that neither the hand seed nor Wiktionary covered —
all full-form, because a corpus row is evidence that a form exists, not a
paradigm.

`cac-freq.tsv` is the compiler's rank input. Counts become an *order* in the
compiler and never reach the artifact: the suite ranks, it does not score
(NL-17), and a frequency table is ranking data rather than training (plan §8).

## What the engine learned this list

Five table changes, all data, each one found by a class of import mismatches
and each verified against school grammar before its golden was frozen:

1. **`hrad-velar` / `pan-velar`** — a velar stem takes the vocative `-u`
   (*roku*, *pracovníku*, never *\*pracovníce*) and the locative plural `-ích`
   (*rocích*, *zákaznících*). The single largest class of noun mismatches in the
   D-O1 sample, exactly as it predicted.
2. **`pan-e`** — the `-é` animate nominative plural (*Athéňané*).
3. **`ruze-ice`** — the bare genitive plural of `-ice` feminines (*ulic*).
4. **`shorten-zero`** — the *other* shortening. `dům` is long in the citation
   form and short everywhere else; `smlouva` is long everywhere **except** the
   cell with no ending (*smluv*). One lexeme never has both, so they are two
   flags rather than one flag with a mode.
5. **`hrad-u`** — the commonest narrowing of `hrad`: a locative singular of `-u`
   alone, without the parent's free doublet.

⚑ **(5) was a defect, not a gap.** `foreign-stem` is a no-op on a stem with no
Latin marker, so `hrad-foreign` generated exactly the right paradigm for `text`,
`plán` and `zub` — and claimed **188 ordinary Czech nouns as foreign
borrowings**. Nothing failed; the artifact was correct and the editorial column
was a lie. The `vzor` column is what the studio shows a human, so a wrong one
there is a wrong fact in front of a reviewer. Found by reading the output.

## The projection rule (the p8-1 task that was in no list)

The engine generates the query-relevant subset (GI-1). Wiktionary's conjugation
table also carries transgressives, passives, adjectival participles and a verbal
noun, so exact equality both directions — what `engine.classify` requires —
rejected **every verb in the source**. The importer therefore matches
one-directionally:

> every cell the ENGINE generates must be present in the SOURCE table and agree
> with it; the source may carry cells the engine does not.

For nouns this is identical to strict classification: the engine generates all
fourteen cells, so "the source covers our cells" means "the source has a
complete table" — the property D-O1 measured and the property an incomplete
extract fails. `engine.classify` is untouched; contracts §4 defines it as exact,
and an importer's tolerance does not belong inside it.

Two smaller things had to travel with it, both as data:

* **The two sides split the paradigm at different depths, in both directions.**
  Wiktionary collapses an adjective's oblique plural into one genderless cell
  where the engine writes three; the engine writes one masculine singular
  l-participle where Wiktionary writes an animate and an inanimate one. A cell
  is compared against every source cell it is *comparable* with, and all of them
  must agree.
* **Two tagset conventions disagree about what is worth saying** — the engine
  writes `VerbForm=Fin` on every finite form and Wiktionary writes none;
  Wiktionary writes `Tense=Pres` on the imperative and the engine writes none.
  Neither distinguishes a cell from a cell (`compare_ignore` in
  `kaikki-tags.yaml`).

## Gaps that remain, and why they are not half-fixed

* **The `-ec` vocative** (*otče*, not *\*otci*) — one of p8-1's four. It cannot
  be done as stated: `palatal` is a **stem-level** flag applied to every cell
  whose ending triggers it, and this alternation is **cell-level** — *otci* in
  the locative and *otče* in the vocative come from the same stem and the same
  vowel. Adding `c → č` to the palatal map would fix the vocative and break the
  locative. It needs a cell-scoped alternation the engine does not have; that is
  a design decision, not an import fix.
* **The imperative of `-it` verbs shortens the stem** (*koupit* → *kup*,
  *platit* → *plať*) while the present does not (*koupím*). Same shape as the
  above and the same reason it is not fixed here: a cell-scoped alternation.
  This is most of the remaining 46% of verbs.
* **Verb patterns the tables do not have at all** — the `nést`/`brát`/`mazat`/
  `sázet`/`umřít` classes. `jet` has no pattern to be classified into. Six verb
  patterns cover a lot of Czech and not all of it.
* **PROPN at 7%** is expected and not a target: proper nouns are the *open*
  vocabulary and belong to world layers (FI-1), which is why `Kaufland` is not
  in this artifact.

## QA — simplemma as a read-only second opinion (β)

`eval/qa-simplemma.md`, over the train side, weighted by token count:
**76.4%** agree, **3.7%** disagree, **19.9%** are forms we have no entry for.

The coverage gap is the design: we import the query domain, not the language.
The disagreements are worth reading — the first one found a real modelling
error. **`tento` was folded into `ten`** as a form; CAC, which is the eval
oracle, lemmatizes *tento* to *tento*, and the head-of-list accuracy metric
would have lost 150 tokens of the train side to it. Split into its own entry.

Two further disagreement classes are *convention*, not error, and p8-4's harness
will have to decide what it measures: CAC lemmatizes every personal pronoun to
`já` (so *nás* is `já`, not `my`) and every possessive to `jeho`. Our hand seed
follows the citation form instead. Neither is wrong; they cannot both score.

simplemma never writes into a layer, and `tests/test_qa_guard.py` asserts that
nothing outside `eval/` so much as mentions it.
