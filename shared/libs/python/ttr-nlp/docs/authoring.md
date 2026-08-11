<!-- SPDX-License-Identifier: Apache-2.0 -->
# Writing a rule pack

For the person who knows the domain. You do not need Python, and if you have
written JAPE before, most of what you know transfers — the second half of this
page is addressed to you specifically.

A pack answers one question: *when the user says this, which query do we run and
with what parameters?* Everything else in the suite exists to put enough
annotations on the sentence for your rules to have something to match.

---

## 1. The shape of an answer

Start from the end. This is what a pack produces:

```yaml
- add:
    type: QueryPattern
    features:
      query: faktury_zakaznika                       # WHICH query to run
      nazev_zakaznika: { from: name, get: "@string" } # and its parameter
```

`query` is an opaque id — the suite never interprets it, it just carries it to
whatever runs queries. The other features are **named exactly as the query's
declared parameters**, and their values are spans of the user's own words.
`ttr-nlp validate --model <dir>` checks both against the model and tells you when
you have typed `nazev_zakaznik` for `nazev_zakaznika`. Run it; that mistake
produces a query that runs with an empty parameter rather than an error.

## 2. The hero, line by line

The real file is `tests/fixtures/packs/valid/hero-cs-invoices.pack.yaml`. The
sentence is *Zobraz všechny faktury od zákazníka Microsoft* — "show all invoices
from customer Microsoft".

```yaml
pack: hero-cs-invoices     # id; also how a pipeline refers to this file
version: 1
phases:
```

**A pack is a list of phases, run in order.** Each phase sees one layer of
annotations and can add more, which the next phase then sees. That is the whole
mechanism, and §3 explains why it is not optional.

```yaml
  - phase: name-candidates
    input: [Token]           # this phase sees ONLY tokens
    control: brill
    rules:
      - rule: ProperNounIsANameCandidate
        lhs:
          - { ann: Token, features: { upos: PROPN } }
        rhs:
          - add: { type: NameCandidate, features: { source: morphology } }
```

Phase one *lifts*: every proper noun becomes a `NameCandidate`. On its own that
does nothing useful. It exists so phase two has something to match when the NER
engine is not available — see §5.

```yaml
  - phase: query-match
    input: [Lookup, ORGANIZATION, NameCandidate]
    control: appelt
    rules:
      - rule: FakturyZakaznikaNer
        priority: 100
        lhs:
          - { ann: Lookup, features: { kind: entity_alias, entity: faktura } }
          - { ann: Lookup, features: { kind: entity_alias, entity: subjekt } }
          - { ann: ORGANIZATION, bind: name }
        rhs:
          - add:
              type: QueryPattern
              features:
                query: faktury_zakaznika
                nazev_zakaznika: { from: name, get: "@string" }
```

Read the `lhs` aloud: *a Lookup meaning "invoice", then a Lookup meaning
"customer", then an organisation — call that one `name`*. Three steps in a row
means three annotations **in sequence**. `bind: name` labels one so the `rhs` can
point at it, and `get: "@string"` takes the text it covered.

Note what is **not** matched: the words *Zobraz*, *všechny* and *od*. They are
Tokens, and this phase's `input:` does not list Token, so they are invisible here
— the three annotations that matter are adjacent as far as this phase can see.
That device is §3.

## 3. `input:` — the one thing to understand first

> A phase sees exactly the annotation types its `input:` lists. Everything else
> is invisible, and matching runs *across* it.

This is the single most useful thing in the DSL and the single most common way to
get a pack wrong. Two consequences:

**Put one layer in a phase.** A `Lookup` the gazetteer laid over a `Token` starts
at the same offset as that token. With both types in `input:`, the Token comes
first and the Lookup is never the next annotation — so it becomes unreachable and
your rule silently never fires. If you need token-level evidence in a rule that
matches Lookups, *lift* it in an earlier phase, as the hero does.

**Adding a type to `input:` can break rules that were working.** It is not
additive: a new visible type inserts itself between the annotations your existing
rules were matching in sequence.

`ttr-nlp validate` catches the other half of this — a rule matching a type its
phase does not make visible is `NLS-PACK-002`, because such a rule can never fire.

## 4. What you can match on

| You write | It matches |
|---|---|
| `{ ann: Token }` | any token |
| `{ ann: Token, features: { upos: PROPN } }` | a proper noun |
| `{ lemma: faktura }` | shorthand for a Token whose `lemma` is *faktura* |
| `{ text: "Praha" }` | shorthand for a Token whose surface text is *Praha* |
| `{ ann: Lookup, features: { entity: faktura } }` | a gazetteer hit for that entity |
| `{ all: [ { ann: Token }, { not: { ann: Lookup } } ] }` | a token that is *not* also a Lookup |
| `{ group: { or: [ [...], [...] ] } }` | either alternative |
| `{ group: { seq: [...] } }` | a sub-sequence, so `repeat` can apply to the whole |
| `{ ann: X, contains: { ann: Y } }` | an X with a Y inside it |
| `{ ann: X, within: { ann: Y } }` | an X inside a Y |
| `{ after: { ann: X } }` | *preceded by* X — matches width zero, consumes nothing |
| `{ notafter: { ann: X } }` | *not* preceded by X |

Modifiers on any step: `repeat: "*"` / `"+"` / `"?"` (or `{min, max}`), and
`bind: <name>`.

Feature values can be a literal, `{ regex: "^INV-" }`, `{ in: [a, b] }`, or a
range like `{ gte: 3 }`.

Two things the validator refuses, both because the rule would fire and write
nothing:

* a `bind:` **inside** an `after:` / `notafter:` / `not:` / `contains:` /
  `within:` — those forms are checked and then discarded, so nothing survives for
  the `rhs` to read. (A `bind:` **on** the assertion step itself is fine.)
* a `repeat:` over something that can match without consuming anything — that
  never terminates.

## 5. Two lanes, one answer

A deployment may not have Czech NER. In that lane `ORGANIZATION` annotations do
not exist, so the hero pack carries a second rule:

```yaml
      - rule: FakturyZakaznikaFallback
        priority: 10
        lhs:
          - { ann: Lookup, features: { kind: entity_alias, entity: faktura } }
          - { ann: Lookup, features: { kind: entity_alias, entity: subjekt } }
          - { ann: NameCandidate, bind: name }
```

Same answer, reached through the morphology that phase one lifted. `priority: 100`
versus `10` decides which wins when both could match, so the NER path is
preferred where NER exists.

**This is your decision to make, not the suite's.** Losing an engine costs the
pack a rule, not an answer — but only if you wrote the fallback. The response
tells you the degrade happened (`NLS-NLP-011`); it will not invent a fallback for
you.

## 6. Control styles

`control:` decides what happens when several rules could fire.

| Style | Behaviour |
|---|---|
| `appelt` | exactly one rule per region: **longest match → highest priority → earliest in the file**. Resumes after the match. The usual choice. |
| `brill` | every accepting match fires; resumes at the furthest end. Use for lifting phases. |
| `all` | every match fires; resumes at the next offset. Overlapping results. |
| `first` | the first acceptance, without extending to the longest. |
| `once` | the phase stops after one firing. |

These are JAPE's, with JAPE's meanings.

## 7. Validate before you push

```bash
ttr-nlp validate packs/ lists/ --model ../model
```

Exit 0 means the service will load it. Exit 1 prints every problem with the
path of the offending line — fix them in one pass rather than one per run.

**It is the same code the cluster runs.** Not a similar check: the service's
boot-time load and its `ReloadPacks` call the same function, so a pack that passes
here passes there. If they ever disagree, that is a bug in the suite and worth
reporting as one.

The codes you will meet:

| Code | Means |
|---|---|
| `NLS-PACK-001` | the file does not parse, or violates the schema |
| `NLS-PACK-002` | it parses, but a rule could never fire (invisible type, unbound reference, duplicate `bind`, non-terminating `repeat`) |
| `NLS-PACK-003` | a gazetteer list file is invalid |
| `NLS-PACK-004` | a pipeline names a pack, phase or list that is not there |
| `NLS-PACK-005` | `--model` only: a query id or parameter name the model does not declare |

And one rule about the whole set: **a source tree loads completely or not at
all.** Three good packs beside one broken one load nothing. A partially-loaded
service would look healthy and silently fail to answer exactly the questions the
broken pack was for.

---

## 8. For JAPE veterans

The semantics are JAPE's; the syntax is not. What maps directly:

| JAPE | here |
|---|---|
| `Input: Token Lookup` | `input: [Token, Lookup]` |
| `Options: control = appelt` | `control: appelt` |
| `Rule: Name` / `Priority: 100` | `rule: Name` / `priority: 100` |
| `{Token.string == "x"}` | `{ text: "x" }` |
| `{Token.kind == word, !Lookup}` | `{ all: [ {ann: Token, features: {kind: word}}, {not: {ann: Lookup}} ] }` |
| `(A \| B)` | `group: { or: [ [A], [B] ] }` |
| `(A)+` | `{ group: { seq: [A] }, repeat: "+" }` |
| `:label` | `bind: label` |
| macros | *(none — write the steps out)* |

Four real differences:

1. **No Java on the right-hand side, ever.** The `rhs` is `add` and `update`, over
   a closed set of getters. This is deliberate (P-2): a pack is data all the way
   down, so it can be reviewed by someone who does not write code and cannot do
   anything a reader cannot see. If you need computation, it belongs in the
   consumer, not the pack.
2. **No macros and no `Phase:` file-per-phase.** One YAML file, phases in a list.
3. **Bindings are rule-scoped and must be unique within a rule** — the same name
   in two branches of an `or` is fine and idiomatic (only one branch matches);
   the same name in two sequential steps is an error.
4. **The gazetteer is deterministic.** Longest match, no scoring, no
   thresholds. Approximate matching lives outside the suite entirely.

The `input:` device works exactly as it does in GATE, and everything §3 says is
already familiar to you. The lifting phase in the hero is ordinary JAPE practice.

---

## 9. Gazetteer lists

Rules match `Lookup` annotations, and lists are where those come from. Full
reference in the wheel's README; the short version:

```yaml
list: dfp-entity-aliases
version: 1
matching: lemma          # exact | ci | lemma | fold-diacritics — PER LIST
source: { world: dfp, origin: "glossary@2026-08-01" }
entries:
  - term: faktura
    features: { kind: entity_alias, entity: faktura }
```

For an inflected language use `matching: lemma` and write the lemma once — the
match happens on what the engine produced, so *faktury*, *faktuře* and
*fakturami* all hit the same entry. That is the difference between one line and
forty.

Every `Lookup` also carries `source` (the list id) and `matching` (the mode),
stamped automatically. You cannot set those yourself, and an entry that tries is
rejected — they are how you trace an annotation back to the file that caused it.
