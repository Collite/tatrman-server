# `calls:` conformance-fixture schema (RG-P6)

A **conversation fixture** replays an ordered list of tool calls against the `resolve.bind:v1`
door and asserts the outcome of each turn. This is the assertion vocabulary the three-tier
conformance harness (RG-P6.S2) consumes; the **refusal-over-guess** seeds authored in S1.T3 live
here from day one and are extended into the full E2E core tier by SV-P4 (with the reference Golem).

```jsonc
{
  "id": "kebab-case-id",                 // unique fixture id
  "description": "what this asserts",
  "turns": [
    {
      "tool": "resolve.bind:v1",          // the door tool id (naming ledger)
      "args": { "conversation_id": "…", "text": "…" },   // the MCP tool args, verbatim
      "scenario": "ambiguous_member",     // S1 seed: names the core behavior to replay.
                                          //   S2 REPLACES this with a live pipeline run driven by
                                          //   fixture-carried nlp-parse + fuzzy-match data — the
                                          //   scenario tag is the seam, not the final input.
      "expect": {
        "outcome": "clarification",       // clarification | resolution | empty | error
        "no_binding_below_threshold": true,   // the refusal-over-guess invariant (always assert true)
        "min_options": 2,                 // clarification only — at least this many distinct options
        "error_code": "INVALID_ARGUMENT"  // error only
      }
    }
  ]
}
```

## Outcomes
- **`clarification`** — `AwaitingClarification`: options + an opaque `resumeToken`. The door offers a
  choice; it does **not** bind. (Instance ambiguity → refuse over guess.)
- **`resolution`** — a `Resolution` carrying ≥1 binding, each with provenance (score ≥ bind threshold).
- **`empty`** — a `Resolution` with **zero** bindings: the core found nothing confident to bind and says
  so honestly rather than guessing. (Below-threshold → refuse over guess.)
- **`error`** — `isError=true` (bad args / identity refusal), with an `errorCode`.

## The refusal-over-guess invariant (`no_binding_below_threshold`)
Every fixture asserts it: the door must **never** surface a domain binding whose provenance score is
below the bind threshold, and must **never** turn an ambiguous or below-threshold span into a guessed
binding. Ambiguity resolves to `clarification`; no-confident-match resolves to `empty`. This is the
door's signature guarantee (RS-27) and the gate the SV-P3 parity bar checks.
