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
