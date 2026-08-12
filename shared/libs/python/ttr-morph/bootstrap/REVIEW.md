# LM bootstrap — the review block (NLS-P9.3 T6)

**For Bora. One pass, and it decides two things.** Everything below was produced
by `ttr-morph bootstrap`, which runs the p9-2 cascade over target word lists and
writes the rows a `morph-studio` would ingest. **Nothing has been ingested.**
That is the design: the batch runs, a person reads this, and only then does
anything reach a queue.

The two runs:

| run | targets | already covered | worked | auto-validated | proposed | nothing |
|---|---:|---:|---:|---:|---:|---:|
| [`core/`](core/bootstrap-report.md) — the analytical target list | 149 | 147 | 2 | 1 | 1 | 0 |
| [`hartland/`](hartland/bootstrap-report.md) — a world glossary | 15 | 4 | 11 | 4 | 7 | 3 |

Reproduce either with the command at the bottom.

---

## ⚑ Finding 1 — the analytical list has nothing left for an LLM to do

147 of 149 target lemmas are already in the artifact after P8, and the two that
are not (`kvartál`, `největší`) took one deterministic guess each. **The LLM
bootstrap, aimed where the task list aimed it, is a two-word job.**

That is not a failure of the batch; it is P8.3 having worked. But it means the
phase's premise — "the remaining uncovered target vocabulary" is a big enough
pool to be worth a model — is no longer true, and the interesting question is
where the next few thousand lemmas should come from instead. Three candidates,
in the order I would rank them:

1. **The CAC frequency tail.** `lexicon/cs/cac-freq.tsv` ranks every lemma the
   corpus saw; P8.3 imported the top slice. The tail below that cut is the
   largest honest pool and is already ranked by how often it actually occurs.
2. **The analytical *verb* list** — where D-O1 re-aimed the bootstrap after
   measuring that the kaikki import reproduces nouns far better than verbs.
   Nothing has been built for it yet.
3. **World glossaries and catalogues**, per world, as below — small each, but
   they are the words a specific demo actually says.

**Decision wanted:** which of those the next batch targets, or that the bootstrap
stands as built and waits for a world that needs it.

## ⚑⚑ Finding 2 — a defect the batch found, and one it cannot fix

The first world run auto-validated *pololetích* as `muzeum-um` with the **invented
lemma *pololetum***. The guesser had already charged its inflected-tail penalty:
0.95 − 0.15 = **exactly 0.80**, the auto-validate line, met rather than missed.

Fixed, and not by moving the constant — the arithmetic was reaching for a
categorical rule, so it is now stated as one: **if the engine's own tables say a
token ends in one of their endings, the deterministic leg may propose it but not
auto-validate it.** It still gets ranked; it goes to a person or to the LLM leg.
Single-character endings stay out of that set on purpose, so *Kauflandu* — the
hero of detailed-design §9 — is untouched, and there is a test saying so.

**What that fix does NOT reach, and I do not think anything can:** the same run
auto-validated *svátky* as `hrad-u` with the lemma ***svátk***. The real lemma is
*svátek*, with the fleeting e. Structurally this is the identical move to
*Kauflandu* → *Kaufland*: strip a plausible ending, get a plausible consonant
stem. One is right and one is not, and **nothing in the surface shape
distinguishes them** — `-y` is a single-character ending and is left out of the
rule for good reason (it is also how many citation forms end).

So: the deterministic leg will keep producing confident, well-formed, wrong
lemmas at some rate, and `auto-validated` genuinely means *"the engine agreed
with itself"* and nothing more. The report calls that bucket "spot-check these"
for this reason. This is the strongest argument I have seen for the LLM leg —
a classifier that knows *svátek* is a word and *svátk* is not, which is exactly
the knowledge the guesser has no way to hold.

## ⚑ Finding 3 — a trigger glossary is not a lemma list

The hartland glossary holds *inflected* forms on purpose (`pololetích`, `sezóně`,
`státě`, `svátky`) — it is a trigger vocabulary for matching spans, and matching
wants the forms people type. The cascade wants citation forms. Feeding one to
the other produces the failures above, plus a batch where a third of the rows
are the same word in two cases.

Not a bug in either. It means **world glossaries need a lemma pass before they
are bootstrap targets**, or the batch needs a lemmatizing front end — which is
Wave C. Worth knowing before somebody points this at a world's whole glossary
and reviews six hundred rows of the same nouns.

Multi-word terms are dropped rather than split, for the same family of reasons:
the demo catalogue's Czech strings are things like *"Farrow Běžecká bota MX-MRS"*,
and splitting that asks the guesser for a paradigm for `MX-MRS`. **The hartland
demo catalogue is therefore not wired as a target** — its Czech content is
multi-word entity names, which belong to the gazetteer lane, and its
single-word category names (*Knihy*, *Umění*) would need a small export on the
hartland side to become a word list. That export is a hartland-repo change and
is the one input named in the task list that this run does not cover.

## What I would do with these rows

`core/bootstrap.jsonl` (2 rows) is fine to ingest as-is. `kvartál` →
`hrad-u` is right; `největší` is a superlative adjective proposed as a `píseň`
noun at 0.60, which is the cascade correctly declining and handing it to a
person.

`hartland/bootstrap.jsonl` (11 rows) I would **not** ingest until the glossary
has had a lemma pass — see Finding 3. Four of its eleven rows are inflected
forms of words already in the batch under their citation form.

## Reproducing

```bash
just morph-compile                       # or point --snapshot at a released artifact
cd shared/libs/python/ttr-morph
uv run ttr-morph bootstrap \
  --targets src/ttrmorph/seed/data/cs/target-words.yaml \
  --snapshot ../../../dist/morph/cs.morph.snap \
  --snapshot ../../../dist/morph/core-kaikki.morph.part \
  --snapshot ../../../dist/morph/core-cac.morph.part \
  --world core -o bootstrap/core
```

Add `--llm` to bring in the classifier leg (`MORPH_LLM_URL`); without it the run
is the deterministic guesser alone, which is a supported arrangement and is how
both runs above were produced. `--llm` with no gateway configured is refused
rather than silently downgraded.

## Then

```bash
curl -s $STUDIO/v1/ingest -H 'content-type: application/json' \
  -d "{\"reports\": $(jq -s . bootstrap/core/bootstrap.jsonl)}"
```

The rows arrive at the status the report gives them — **the studio does not run
the cascade again**, which is what makes this document a description of the
queue rather than of a different run. Then work the queue in the FI-7 UI;
`verified` is the human act, and `POST /v1/export` is gated on it.
