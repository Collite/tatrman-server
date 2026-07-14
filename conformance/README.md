<!-- SPDX-License-Identifier: Apache-2.0 -->
# Conformance — the three-tier instrument (RG-P6.S2)

The Resolution & Grounding parity instrument (RS-30). Its **gating** tier is the SV-P3 "parity
demonstrated" gate; the other two tiers seed the fuller E2E coverage that SV-P4 authors.

| Tier | Runnable | Gating? | What it asserts |
|---|---|---|---|
| **Service-level** | `just conformance-service-level` | **YES** (CI) | The three service-level corpora pass, hermetic + self-contained + **no DFP dependency**. |
| **E2E core** (`calls:`) | `services/ttr-resolver/.../conformance/calls/` seeds (`CallsSeedConformanceTest`, `RefusalOverGuessConformanceTest`, gated) | drivable seeds run vs the REAL pipeline; full at SV-P4 | Multi-turn door conversations. The refusal-over-guess + clarification-round-trip seeds drive the actual `ResolverPipeline` (SpanProposal → GateSpans → HMAC codec) via hermetic nlp/fuzzy fakes; `seed_only` fixtures (hero, geo-dark) are shape-validated pending the live SV-P4 stack. |
| **Extended** (pilot) | `just eval-grounding` (live) | **NO** (scored-not-gating) | Live grounding eval over the pilot corpus (arrives via RO-19 ask ③). Reports pass-rate; never fails CI. |

## The gating service-level tier — `just conformance-service-level`

Runs three **hermetic** service-level corpora (zero live services, zero DFP):

1. **ENTITIES_ONLY** (resolver) — `Q20ParityTest` over
   `services/ttr-resolver/src/test/resources/q20/ucetnictvi_entities_only.jsonl` (12 cases).
   Replays the recorded Q-20 span-gating behavior through the real `GateSpans`. Acceptance baseline
   from the vendored spike (config C: **P=1.0, ucetnictvi R≥0.8, 0 spurious, awaiting 1/5**).
   Provenance: `q20-spike-results.json` + `PROVENANCE.txt` (numbers cited, never recomputed).
2. **Q-17 match-quality** (fuzzy) — `MatchQualityCorpusTest` over
   `services/ttr-fuzzy/src/test/resources/match-quality-corpus.jsonl` (40 cases: diacritics /
   inflection / multi-word-order / typos), lemma axis ON, asserting the expected top id per case.
3. **Grounding hermetic** — `eval/grounding/tests/` (`test_corpus_valid.py` + `test_report.py`,
   18 checks): corpus-validity of the 109-case bulk + 21-case e2e corpora, and the pure `report.py`
   scoring logic. The **live** run (`run_eval.py` → grounding-mcp + Golem) is the extended tier, NOT here.

### Corpus provenance (hashes — ENFORCED; bump on any deliberate change)
These are verified on every gate run by `just conformance-verify-hashes` (reading
`conformance/corpus-hashes.sha256`); a silent edit — even whitespace/reordering a
semantic test would miss — fails the gate (RG-P6 review I). Keep the two lists in sync.
```
ucetnictvi_entities_only.jsonl  d0e8b17fa6e989ff9e17bd4a035825946e2b551802d1edd38d3bd163676331f5
match-quality-corpus.jsonl      4f4daa416dff6c40227887ff9573903ca489ee8c5fb0e0a5387a52134f1310e2
grounding-cases.json            a54491aa20ee4eac37b68c9b12a74658ed9b88e03051533289ce506e63590100
e2e-cases.json                  0034ed387f31c1dca8ba1a56b22b72f968193ee5f1aacb0f20aa7336facf77f7
```

### Why hermetic (no DFP)
The gate must be green in CI without a deployed stack or any DFP client — that is the SV-P3 promise.
(The grounding leg's one network touch is a one-time install of the **pinned** test deps in
`eval/grounding/requirements-test.txt`, skipped once present — no unpinned/floating package, so the
run stays reproducible; RG-P6 review H.)
The live end-to-end (parse → fuzzy → grounding → door) is intentionally deferred: the E2E core tier is
hand-authored `calls:` fixtures (SV-P4 with the reference Golem), and the extended tier scores the pilot
corpus without gating. What is gated here is **service-level parity against recorded spike/referee
numbers** — the deterministic behavior each service must not regress.

CI: `.github/workflows/ci.yml` job `conformance` runs `just conformance-service-level` on every push/PR.

## S-2 fold audit (RG-P6.S2.T4)

The one normalization spec is `shared/libs/kotlin/ttr-text` → `Normalization.fold` (lower → NFD → strip
combining marks); its golden vectors (`NormalizationSpec`) are the fixture. **No understanding-layer site
keeps a private fold.** Call-site status:

| Site | Status |
|---|---|
| ttr-fuzzy (`TextNormalizer.fold`) | ✅ shared (RG-P0.S3) |
| resolver span kernel (`SpanProposal`) | ✅ shared (RG-P5) |
| chrono / money recognizers | ✅ shared (RG-P3) |
| geo span parser (`GeoSpanParser`) | ✅ shared |
| geo `PlaceResolver` / `BoundaryStore.foldPlaceKey` / `GeoCorpusSpec` | ✅ **converged in RG-P6.S2.T4** — private folds removed; `trim()` kept as geo's visible input pre-step; `FoldParitySpec` locks byte-identity to the shared spec. (The old private copies stripped `\p{M}` vs the shared `\p{Mn}` — output-neutral on Czech place names.) |
| **meta.search** `ttr-metadata` `Tokenizer.fold` (tatrman repo → consumed by veles) | ⚠️ **documented characterization bridge — the one remaining copy.** It folds in a **different operation order** (NFD → strip → lowercase, vs the shared lower → NFD → strip), and lives in a *different repo* + a *published* artifact. Converging it requires: (1) a characterization test over veles's keyword corpus proving the op-order swap is output-neutral there; (2) a `ttr-metadata → org.tatrman:ttr-text` dependency; (3) a `ttr-metadata` republish. Tracked as the T4 follow-up — not swapped blind (behavior + cross-repo + release risk). |

