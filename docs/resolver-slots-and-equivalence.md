# Slots and the two mention-homonymy rules (MH)

> `services/resolver` · the Binder. Companion to the frame-role tables in
> `services/resolver/src/main/resources/frame-roles.conf`.

## The problem

One word, two objects. A **channel is named after the thing that composes it** — the Stores
channel is made of stores — so on a modelled estate the bare word `prodejna` / `stores` is
claimed twice:

```
'prodejna' [cs]  ->  er.entity.store        (the DIMENSION: six rows, countable, groupable)
                 ->  er.entity.store_sales  (the FACT the Stores-channel vocabulary is pinned to)
```

Neither owns the other, so the declared-containment collapse does not apply, and both are `V:`
identities inside the Binder's tie band. The verdict is `Ambiguous` → a clarification. The Binder
is **right** on that evidence: these are two unrelated objects. What MH adds is more evidence.

The collision is systematic, not one estate's quirk: wherever a structural discriminator's member
coincides with a dimension — channel, warehouse-as-place vs warehouse-as-fulfilment,
region-as-org vs region-as-geography — the bare word will be claimed twice.

## Rule 1 — the slot decides a CROSS-KIND tie

Since the mention facet shipped, the two candidates differ in `object_kind` (`entity` vs
`entity_with_measures`). And the sentence already says which kind it wants.

`SlotHints.stamp` runs in the pipeline right after span proposal — where the parse is in scope,
because `GateSpans.gate` receives candidates only — and stamps `DomainSpanCandidate.slot`. The
Binder reads `candidate.slot`; a re-gate has no parse, gets `SlotHint.NONE`, and both rules are
no-ops there by construction.

| slot | signal | prefers |
|---|---|---|
| `COUNT_HEAD` | a count quantifier on or beside the head (`kolik`, `how many`, `number of`) | `entity` |
| `GROUP_BY` | a grouping preposition (`podle`, `by`) | `entity`, `attribute` |
| `GOVERNED_VALUE` | the span governs a value (`prodejna Nashville`) | `entity` |
| `FILTER` + measure-capable head | a filter preposition under a measure (`tržby z prodejen`) | `entity_with_measures`, `measure` |
| `FILTER` without | the same shape under a plain entity head (`vratky z prodejen`) | *(nothing — see rule 2)* |
| `COORD_WITH` | a `conj` sibling (`srovnej prodejny a web`) | the sibling's kinds |
| `SUBJECT`, `NONE` | — | *(nothing)* |

It is a **preference**, not an admissibility filter: dis-preferred kinds are dropped only when a
preferred candidate remains. It never empties the band, never promotes anything from below it,
and never touches an `M:` row — a data value is a different species, and "which object does this
word name" is not a question asked of it. A tie between two objects of the **same** kind is
genuine homonymy and still refuses; that is the definition, not a gap.

## Rule 2 — declared reachability decides whether the two readings are the SAME ROWS

The slot says which kind. This asks the harder question, and answers it from declarations rather
than from assumption.

The archive carries, per entity, the facts that relate **to** it and whether the relation is
mandatory (`targets[ref].reachedFrom`, schema `ttr-lexicon-compiled/v3`, derived from
`def relation` + `cardinality.to`). With **D** the dimension, **F** the fact the channel term is
pinned to, and **H** the fact the clause is about (`SlotHint.headRefs`):

| | condition | outcome |
|---|---|---|
| 1 | H is outside D's reach and is not F | neither reading is about this clause — say nothing |
| 2 | H **is** F and the reach is **mandatory** | the readings select the same rows: bind **D** (group-by and member filters stay possible) and record F as an equivalent reading |
| 3 | H is reachable from D but is a **different** fact | the channel term is pinned to the wrong fact — bind **D** |
| 4 | H **is** F but the reach is **nullable** | the join drops rows the restriction keeps: the readings **differ** — admit both and ask |

Rule 4 dominates 2 and 3 for a pair: an estate declaring both a mandatory and a nullable relation
between one pair is contradicting itself, and refuse-over-guess decides. With no `headRefs` —
a bare word, a fragment, a re-gate — nothing fires at all, so a one-word question keeps asking.

Rule 3 is why the two rules ship together: on its own the slot rule prefers the only
measure-capable candidate, and under a returns head that candidate is the wrong fact.

## What comes out

- **`Binding.equivalents`** — `[{ref, rule}]`, `rule = "reach-equal"`. The reading rule 2
  suppressed, so an answer can say *"read as the Store dimension; on this model that is the same
  rows as the Stores channel"*. It is **disclosure**: a consumer may surface it and must not
  re-plan on it. The claim is **equal by DECLARATION** — two readings can be declared-equal and
  still differ on dirty data (an orphan FK no constraint enforced).
- **`Option.object_kind`** on a clarification option — the option's species, so a residual
  question can be worded *"the stores (a dimension), or the Stores channel (sales)?"* instead of
  two labels a user cannot tell apart.

## Configuration

`frame-roles.conf` gains `count-heads` beside `grouping-preps` / `filter-preps` — per-language
data, because the next language is a data change. A config file written before MH has no such
key, loads unchanged, and simply never produces a `COUNT_HEAD` slot.

## Compatibility

Every input the two rules read is defaulted and absent-tolerant, so an estate that declared
nothing behaves exactly as it did before:

| input absent | effect |
|---|---|
| pre-v3 archive (no `reachedFrom`) | reachability rule inert; the slot rule still works |
| no `object_kind` (no mention facet declared) | both rules inert — nothing to prefer |
| no `count-heads` in an override config | `COUNT_HEAD` never fires |
| a re-gate (no parse) | `SlotHint.NONE` — both rules inert |
| `Registry` override without `reached_from` | reachability rule inert |

No reader gate is needed for the v2 → v3 archive crossing: `reachedFrom` is a defaulted field
inside `targets`, so a v2 archive decodes in a v3 reader and a v3 archive is read by an older
reader as a v2 one.
