<!-- SPDX-License-Identifier: Apache-2.0 -->
# `frame-roles/` — the Q-15 spike corpus, re-run in process

Copied verbatim (2026-08-05, RV-P2.1.T5) from
`project/kantheon/features/resolving/implementation/spikes/frame-roles/`:

| file | what it is |
|---|---|
| `fixtures.yaml` | 39 fixtures / 137 mentions with **gold** frame roles, authored 2026-08-02 **before any deriver existed** |
| `holdout.yaml` | 8 fixtures / 29 mentions, authored **after the rules were frozen** and run once |
| `parses/*.json` | the real cached Stanza parses for every fixture question |
| `results-main.json`, `results-holdout.json` | the scores the spike recorded — this suite's floor |

`FrameRolesFixtureTest` re-runs both corpora against the ported Kotlin rules and asserts
**at least** the recorded numbers. It is a regression floor, not a re-derivation of the
verdict: Q-15 was ruled on the spike's evidence (SUBJECT precision **1.000** against a 0.85
bar, on both corpora), and this suite exists so that the port — and everything that touches
it afterwards — cannot quietly fall below it.

**Quote the held-out row.** The main corpus numbers are a fit: the spike's T4 was "run
harness; fix rule gaps", so the rules were iterated against the fixtures that score them. The
held-out set was authored after the freeze and run once, and that is the actual evidence.

## Two known misses, both deliberate

- **`hl-unbound-member-cs#2` — `čerpacích stanic`** (main, gold FILTER, predicted none). The
  spike's failure class **F-2**: a bare genitive modifier is structurally identical to the
  measure's own complement (`tržby ČERPACÍCH STANIC` restricts; `dostupnost ZBOŽÍ` does not),
  and *no parse feature separates them*. The report's recommendation is explicit — leave it,
  do not spend an LLM rung on ~1 mention in 80. Still a miss here.
- **`ho-verb-measure-cs#2` — `loni`** (held-out, gold FILTER, predicted none **in the spike**).
  Failure class **F-1**, and the report's handoff item 4 says to fix it at P2. The port does:
  `advmod` with no preposition, on a mention that binds an **attribute**, is a FILTER. So this
  corpus now scores *above* its recorded floor. The report writes the rule as "advmod +
  Calendar binding"; the rules may not match a ref string (report handoff, and the deriver's
  own contract), so the model fact used is the object KIND rather than the dimension's name.

## The one structural difference from the spike harness

The spike supplied each mention's `target_class` / `object_kind` **from the fixture** because
no cluster was reachable; so does this suite, for the same reason and with the same
consequence: it tests *role assignment*, not the core's binding. The spike's own deferral —
re-run the harness against a live `resolve.bind` — is unchanged and still owed; it is recorded
in `p2-1-lattice-proto.md`. What this port adds on top of the spike is that the SAME rules now
also run inside the pipeline, where the bindings are real, and three golden lattices
(`lattice/`) pin the result end to end.

---

## MS-P3·S4 (2026-09-01) — the fixture stops stating the kind

*Appended, not merged into the history above: everything before this line is the spike's
record and stays as written.*

### What changed

Until this stage every mention here carried `binding.object_kind`, hand-written. That is
**issue #69's second problem**, and it is worse than a shortcut: the rules were being scored
against a vocabulary no producer had ever computed, so R2 could pass on 39 fixtures while
being dead in every deployment — which is exactly what happened, for a year. `PROVENANCE.md`
called it out as "the one structural difference from the spike harness"; MS closes it.

The fixture now states the **model facts**, and `MentionKinds` — the single derivation table
in the ecosystem, imported from `ttr-semantics`, the same one the lexicon compiler calls —
turns them into the kind:

```yaml
model:
  "md.measure.revenue":          {isAttribute: true, listedAsMeasure: true}   # -> measure
  "md.dimension.Calendar.year":  {isAttribute: true}                          # -> attribute
  "er.entity.sales":             {isAttribute: false, ownerHasMeasures: true} # -> entity_with_measures
  "er.entity.store":             {isAttribute: false}                         # -> entity
```

| key | meaning | absent |
|---|---|---|
| `isAttribute` | attribute/column, as opposed to entity/table | `false` |
| `ownerRef` | the owning entity's ref. Carried for the archive; `MentionKinds.of` does not consult it | `null` |
| `listedAsMeasure` | this attribute appears in its owner's `measures:` list | `false` |
| `ownerHasMeasures` | this entity's `measures:` list is non-empty | `false` |

A ref with **no** `model:` entry derives `""` — the estate that declared no mention facet,
representable in a fixture exactly as it is in an archive. A fixture that states
`object_kind` directly is a **load error** naming this file.

### The port changed no expectation

The sweep rewrote bindings only: **not one `roles:` line moved**, and the score table is
identical to the one measured immediately before it, on both corpora —

| corpus | SUBJECT | MEASURE | FILTER | GROUPING | mismatches |
|---|---|---|---|---|---|
| `fixtures.yaml` | P 1.000 R 1.000 | P 1.000 R 1.000 | P 1.000 R 0.974 | P 1.000 R 1.000 | 1 (`hl-unbound-member-cs#2`, the F-2 miss above) |
| `holdout.yaml` | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | none |

Two old kind spellings have no v3 equivalent and were mapped by what the RULES do with them,
which is nothing: `dimension` → `{isAttribute: false}` (→ `entity`), and `member` → no entry
at all (→ `""`). No rule has ever read either string; the identical score table above is the
evidence, not the argument.

### `ms.yaml` — the four MS families

A separate corpus, because `fixtures.yaml` and `holdout.yaml` are frozen evidence and adding
to them would change the denominators of a published score. It is asserted **exactly** —
every mention's role set, not a precision floor — and its texts are reused character for
character from `fixtures.yaml`, so the parses are the same real cached Stanza parses. What
differs is the model behind the words.

| fixture | shape | what it pins |
|---|---|---|
| `ms-grouping-cs` | *"Zobraz tržby podle prodejen"* | a capable entity keeps SUBJECT and takes no MEASURE (MS-R6) |
| `ms-orderby-cs` | *"Zobraz prvních 10 produktů podle tržby"* | **the load-bearing one** — a capable entity under `podle` is exempt from GROUPING |
| `ms-listing-cs` | *"Jak se vyvíjely tržby z tržiště v roce 2025?"* | the exemption does not spread: neighbours still FILTER |
| `ms-kolik-cs` | *"Kolik objednávek jsme měli v roce 2025 podle měsíce?"* | the count shape — no MEASURE, no GROUPING; the READING stays the planner's |
| `ms-undeclared-cs` | `ms-orderby-cs` with no `model:` | contracts §10's last row, executable: declare nothing, get the pre-MS roles |

### The two D3 regressions are guarded elsewhere

This corpus tests **role assignment only** — it never runs span proposal or the binder. The
two competition defects design.md §10.2 found are pinned beside the code that fixes them:

- **the single-word double-bind** → `SpanProposalTest`, *"a single-word anchor declared for
  two owners proposes ONE candidate carrying both"* and *"which owner survives is no longer
  registry order"* (MS-P3·S1).
- **the G2-that-must-not-ask** → `BinderTest`, *"an entity tied with its OWN attribute binds
  the attribute"*, and end to end in `GateSpansTest`, *"the shared-anchor span binds the
  ATTRIBUTE instead of asking"*, which carries a no-`ownerRef` control that still clarifies
  (MS-P3·S2).

### What is still owed

The spike's own deferral is **unchanged**: this suite still supplies each mention's
`target_class` and its *binding*, so it tests role assignment and not the core's binding. MS
closed the `object_kind` half of that gap — the kind is now derived from declared facts by the
real table — and the rest (re-running the harness against a live `resolve.bind`) is still
recorded in `p2-1-lattice-proto.md`.
