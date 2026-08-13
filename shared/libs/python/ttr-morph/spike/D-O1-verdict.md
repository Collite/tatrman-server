## Verdict — read this part

*Hand-written. `kaikki_coverage.py --verdict` appends it verbatim, so
regenerating the measurements above never overwrites it.*

**D-O1 answers yes: kaikki can seed this lexicon, and the English Wiktionary
edition is the one to import from.** The plan does not need re-weighting toward
the LLM bootstrap; it needs the bootstrap pointed somewhere else.

### The three numbers that decide it

1. **Target coverage 79%** (118 of 149 analytical lemmas carry a real
   inflection table in the English edition; 70% in the Czech one).
2. **Reproduction 60%** — of ~2,000 noun tables sampled across the whole
   alphabet, the paradigm engine regenerates three in five *exactly*, so those
   import as four-line vzor entries rather than as walls of forms.
3. **Table completeness** — 9 of 812 unreproduced English tables are short of
   the 14 cells a Czech noun has. In the Czech edition it is 1,052 of 1,483.

That third number is the one that picks the source. The Czech Wiktionary
*knows* Czech better, but its extracted tables are mostly incomplete (usually
missing the vocative), and an incomplete table cannot be classified — exact
match is the whole point of D-F1-α. The English edition's `cs-ndecl` templates
produce full paradigms. **Import from `cs-enwiktionary`, keep the Czech edition
as a second opinion for entries the first one lacks.**

Both are Wiktionary-derived and therefore CC BY-SA: a separable share-alike
member file with its own NOTICE (C-F3/S-2), never merged into a suite-licensed
layer. Nothing on the poison list (D-ε/Q-6) was touched.

### The 40% that does not reproduce is mostly four fixable shapes

The commonest endings among complete-table misses are `-ník` (34), `-ice` (33),
`-sta` (22), `-tel` (16), `-ček` (15), `-dlo` (15), `-nec` (13), `-lek` (13).
Reading the tables behind them, four gaps in the P8.1 inventory account for
most of it, and each is a data edit rather than a design question:

1. **The velar vocative.** A masculine stem ending in *k/h/ch* takes `-u`, not
   `-e` (`pracovníku`, never `*pracovníce`). That alone covers `-ník`, `-ček`
   and `-lek` — over 60 of the sampled misses.
2. **The `-é` animate plural.** `-an` and `-sta` nouns take `Athéňané`,
   `turisté` beside the plain `-i`. A `pan-an` narrowing with both.
3. **`c → č` in the palatal map**, for the vocative of `-ec` nouns
   (`Brazilče`). The `-ec` type is otherwise already expressible — `muž` plus
   `fleeting-e` — so this is one map entry.
4. **`-ice` feminines** take a zero genitive plural (`ulic`), not `růže`'s
   `-í`.

I did **not** make these edits at P8.1. The inventory this phase was asked to
build is B-O5's, the measurement above is of the engine as that phase leaves
it, and changing the tables after measuring would mean reporting a number for
an engine that no longer exists. They are the first work of the importer task,
where re-running this spike is the acceptance check.

### What this means for seeding

The pipeline order in design §6 stands, with the weights it implies:

- **kaikki import does the bulk** of the open analytical vocabulary — better
  than the 60% headline, because the four fixes above are cheap and because a
  non-reproducing table still imports, just as a full-form entry with
  `LM-MORPH-005` rather than a compact one.
- **The hand seed stays essential and gets no help here.** The function-word
  group scores 9 of 29, and that is not a defect of the source: prepositions
  and conjunctions have no inflection table because they have no inflection.
  The closed class is hand-authored, as planned, and it is small.
- **The LLM bootstrap's target changes.** It was budgeted for the general gap;
  the real gap is narrower and more specific — the words absent from both
  editions. In the English extract that is two of 149 (`zobrazit`,
  `pololetí`), and `zobrazit` is the imperative the primary hero opens with.
  Verbs are where both sources are weakest (34% of verb entries carry a table,
  against 66% of nouns), so **the bootstrap should be aimed at the analytical
  verb list**, not spread evenly.

### Caveats

- The reproduction figure is nouns only. Verb tables were not classified: the
  engine's verb patterns cover the query-relevant subset (GI-1), while kaikki
  tables carry transgressives and passives it deliberately does not generate,
  so an exact match would fail for a reason that is not a defect. The importer
  will need a projection to the generated subset before it can classify a verb
  — **that is a real p8-2/p8-3 task and it is not in any task list yet.**
- Both extracts were pulled 2026-08-12. The `-RELEASE` snapshot must record the
  extraction date per share-alike layer (S-2); these are the dates.
- `PROPN` in the English edition looks spectacular (9,237 tables, 92%) and is
  the least useful column here: proper nouns are world-layer vocabulary
  (LM-10), not core, and a world does not want Wiktionary's list of
  place names.
