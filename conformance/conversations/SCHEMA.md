<!-- SPDX-License-Identifier: Apache-2.0 -->
# `conversations/` — the conformance-conversation fixture schema (RV-P4.4)

A **conversation fixture** replays an ordered list of turns through a GOLEM and asserts
the outcome of each. It is the multi-turn tier above the door-level `calls:` fixtures in
`services/resolver/src/test/resources/conformance/calls/`, and it is the corpus RV-28
means by *"one corpus, one core, N shells"*: the same files must pass through the Python
OS Golem (RV-P4) and the Kotlin platform Golem (RV-P5), unchanged.

## ⚑ T1 decision — these EXTEND the `calls:` vocabulary; they are not a second schema

Recorded rather than assumed, because the alternative was live: a sibling schema would
have been easier to author and impossible to keep aligned. What is reused verbatim:
`id` · `description` · `turns[]` · `tool` · `args` · `expect` · the
`no_binding_below_threshold` invariant · naming a lattice golden by id (the P2 gate
fixture already writes `"fixture": "h1prime-cs"`). What is added, and why:

| Addition | Why it could not be reused |
|---|---|
| `tool: "golem.turn:v1"` / `"golem.resume:v1"` | the Golem is not the door; a turn is a conversation step, not an rpc |
| `core: {lattice, resume_token, options[]}` | a door fixture IS the core; a Golem fixture must say what the core answered |
| `gate: {outcomes[], updated_gaps[], rung_log_entry}` | same reason, for `resolve.gate:v1` |
| `pin: {option_id \| free_text \| escape}` | the door has no user; a conversation does |
| `invariants[]` | stated in the file, per P2.4's rule, so a second shell inherits the WORDS and not only the assertions |
| `replay_of: <n>` | at-least-once delivery is the norm; a suite that never redelivers cannot catch the bugs it causes |

**Location is the other half of the decision.** These live at the repo root under
`conformance/`, beside `corpus-hashes.sha256`, rather than inside one service's test
resources — a corpus two shells must share cannot be owned by one of them.

## The shape

```jsonc
{
  "id": "h1-answer",                     // kebab-case, unique
  "description": "what this asserts",
  "surface": "golem",
  "corpus": "hartland_cz",               // the estate the lattices come from (RV-28)
  "invariants": ["an ask is not an answer", "…"],
  "turns": [
    {
      "tool": "golem.turn:v1",
      "args": { "conversation_id": "…", "turn_id": "…", "text": "…",
                "locale": "cs", "caller_subject": "…", "profile": "CHAT_QUICK" },
      "core": {                          // what the recorded core answers with
        "lattice": "h1-cs",              // a golden id — never a copy of the lattice
        "resume_token": "…",             // only when the turn should pause
        "options": [ { "id": "opt-1", "label": "…", "ref": "…" } ]
      },
      "expect": {
        "outcome": "answer",             // answer | ask | refusal
        "llm_invocations": 0,            // asserted NUMERICALLY — 0 is the H1 claim
        "asks": 0,
        "measures": ["md.measure.cost"], // what compose selected
        "operators": ["op:show"],        // what it was taught to do
        "gaps_carried": ["GAP_KIND_G1_UNBOUND"],
        "refusal_reason": "NO_CAPABLE_PLUGIN",
        "no_binding_below_threshold": true
      }
    },
    { "tool": "golem.resume:v1", "pin": { "option_id": "opt-1" }, "gate": { … } },
    { "tool": "resolve.gate:v1", "hypotheses": [ … ], "gate": { … } }
  ]
}
```

## The invariant vocabulary

Inherited from P2.4 — *"a hypothesis is not evidence"* — and extended by this tier:

- **an ask is not an answer.** A paused turn has produced nothing; a suite that counts
  it as output would report a Golem that only ever asks as passing.
- **a refusal is not an error.** `RefusalWithGaps` carries the lattice and proves the
  understanding. A runner that treats it as a failure cannot express H4 at all.
- **a hypothesis is not evidence — including the user's.** A pin becomes a binding only
  by surviving `resolve.gate:v1` (RV-7). The user is the proposer most likely to name
  something the vocabulary does not have.
- **the token is the core's, the snapshot is ours.** They travel side by side and are
  never merged.

## Provenance

The lattices are the resolver's own goldens under
`services/resolver/src/test/resources/lattice/` — the same files the Kotlin core is
byte-compared against (RV-P2.1 T2 / P2.5 T4), whose parses are the RV-P0.2 spike's real
cached Stanza output. Nothing here re-authors a lattice; a fixture that needed a new one
would need a new golden first, in the core's own suite, where it belongs.

Every fixture in this directory is hash-pinned in `conformance/corpus-hashes.sha256`.
