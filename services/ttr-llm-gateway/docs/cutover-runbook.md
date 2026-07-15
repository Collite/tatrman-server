# LLM Gateway 1.x → 2.0 — cutover runbook

> Scope: repoint the deployed `ttr-llm-gateway` from the Spring 1.x image to the Ktor 2.0 image
> with **zero caller changes** (G-3). Authored in LG-P6·S1·T6. The GATE that authorizes running
> this runbook is LG-P6·S2 — do not execute until every pre-flight below is green.
>
> Owners: **Bora** (release + olymp repoint + go/no-go) · ops (soak window, ingress). Consumers
> stay untouched (Hebe/Kleio/Themis/Iris/pinakes/Pythia).

---

## 0. Facts this runbook is built on (verified in P6·S1, not assumed)

| Fact | Evidence |
|---|---|
| Prod runs the **1.x** image today | olymp `clusters/{collite-o1,bp-dsk}/apps/llm-gateway/values.yaml` → `image.tag: "0.9.0"`; no `ttr-llm-gateway/v*` release tag pushed yet |
| Cutover = an **image-tag bump** (same Service, same chart) | chart `services/ttr-llm-gateway/k8s`, `config.json chartRevision: master`; only `image.tag` changes |
| Release path builds `ghcr.io/collite/ttr-llm-gateway:<v>` via Jib | `.github/workflows/release-image.yml` — push tag `ttr-llm-gateway/v2.0.0` (or dispatch); RC tags e.g. `2.0.0-rc.1` are fine on GHCR |
| **Rollback is schema-safe** — 1.x tolerates the 2.0 migrations | V2 = new tables only (`teams`/`virtual_keys`/`budget_usage`, all `CREATE TABLE IF NOT EXISTS`); V3 = `ADD COLUMN IF NOT EXISTS`, every new column nullable or `DEFAULT FALSE`; the 1.x writer `PostgresPromptLogRepository` inserts an **explicit** column list (`user_id…created_at`) that omits every new column; 1.x persistence is **Spring Data JDBC**, not JPA/Hibernate → **no `ddl-auto` schema validation runs at startup**, so extra columns/tables cannot fail 1.x boot |
| 2.0 keeps the 1.x columns populated | `PromptLogWriter` writes `user_id/model_name/provider/prompt_text/response_text/tokens_prompt/tokens_completion/duration_ms/status` — 1.x reads stay valid on rows written during the 2.0 window |

### ⚑ Blocker to fix BEFORE cutover — the rollback git anchor is wrong
`git tag llm-gateway/pre-2.0` currently points at **`5e9e104`** (the `llm`-branch tip — the *2.0 rewrite*),
not the pre-rewrite Spring tree it claims to preserve. The real last-Spring-1.x commit is
**`f8ecf74`** (`bdcf6af^`, "chore: bump translator + releases"; `bdcf6af` is the rewrite). Re-cut the
tag before relying on it as the porting/rollback source of truth:

```bash
git tag -f llm-gateway/pre-2.0 f8ecf74      # Bora — destructive tag move, do consciously
git push --force origin llm-gateway/pre-2.0
```

(The rollback *image* `:0.9.0` is unaffected — it lives in GHCR. This only fixes the source anchor.)

---

## 1. Pre-flight gates (all must be green — from P6·S1)

- [ ] **G-1 gate 1 — contract diff clean.** `ContractDiff` run (corpus vs staging 2.0) shows only the
      expected additive deltas (dual usage names, `cached`/`cost`, `X-Gateway-*` headers) and each
      dropped-field delta shows ZERO consumer reads. Report committed at
      `contract-diff/report-2.0.0-rc.md`. Any `UNEXPECTED` delta dispositioned by Bora.
      *Tooling note:* the engine is the CI-gated `ContractDiffSpec`/`ContractDiff` (not the
      `diff.main.kts` the task text names — see `contract-diff/README.md` deviation).
- [ ] **SQ-2 removal warrant** (T4) recorded: async-jobs + `/v1/conversations` endpoint confirmed
      zero-consumer; the Responses **field** surface (`output[]/status/createdAt/conversationId/
      reasoning.summary`, top-level `content`, `conversation` request field) dispositioned — **Iris**
      and **pinakes** either migrated or the surface is retained. **No field-surface removal ships
      until this is signed off.**
- [ ] **Staging soak green** (T5): Hebe/Kleio/Themis/Golem/Pythia smoke clean over the agreed window;
      budget-settle sanity (settled ≈ Σ usage.cost); no 10× latency regression; SSE heartbeats survive
      ingress buffering (`proxy_buffering` off on the Service path — config noted in findings).
- [ ] **Rollback rehearsed** on staging (T6): repoint staging to `:0.9.0`, confirm 1.x serves against
      the migrated PG copy.

---

## 2. Cutover (LG-P6·S2 — the gated step)

1. **Release the image.** Push tag `ttr-llm-gateway/v2.0.0` (or dispatch `release-image.yml` with
   `version=2.0.0 modules=ttr-llm-gateway`). Confirm `ghcr.io/collite/ttr-llm-gateway:2.0.0` exists.
2. **Migrations** — already applied additively during soak (V2/V3). No cutover-time DDL. Confirm the
   prod PG is at V3.
3. **Flush the Redis cache (G-1).** The 2.0 `CacheEnvelope` format is new; stale 1.x entries must not
   be read. `FLUSHDB` the gateway's Redis logical DB (or delete `llm-gateway:chat:*`) at repoint.
4. **Repoint, one cluster at a time.** In olymp, bump `image.tag` `0.9.0 → 2.0.0` in
   `clusters/collite-o1/apps/llm-gateway/values.yaml`, sync/roll, watch (§4). When green, repeat for
   `clusters/bp-dsk/apps/llm-gateway/values.yaml`.
5. **Removals** (only after SQ-2 sign-off): gRPC + protos, `/api/v1` (post-Kleio `/v1`), async jobs +
   NATS — land as follow-ups, not inside the repoint.

---

## 3. Abort criteria (roll back if ANY, during the watch window)

- 5xx / gateway-error rate above the 1.x baseline for >5 min on the repointed cluster.
- Any consumer smoke breaks (Hebe turn, Kleio `/v1`, Themis/Golem alias, Iris responses, pinakes
  `content`, Pythia cost read).
- Budget settle drift (settled materially ≠ Σ usage.cost) or prompt-log writes silently dropping
  (`llm_gateway_promptlog_dropped_total` climbing).
- SSE streams stall under the ingress (heartbeat starvation).

## 4. Rollback (fast — image-tag flip)

1. In olymp, set `image.tag` back to `0.9.0` on the affected cluster; sync/roll. 1.x serves
   immediately — schema is verified-tolerant (§0), no DB step needed.
2. Re-flush Redis (drop any 2.0 envelopes 1.x can't read; 1.x re-populates its own format).
3. Capture the failing signal (diff delta / consumer error / metric) into the P6·S1 findings before
   re-attempting.

## 5. Post-cutover watch window

48 h: error-rate + budget-settle sanity; record in the P6·S1 findings. Then retire the 1.x image and
close the effort (memory/STATUS).

---

## Owners & sign-off

| Step | Owner |
|---|---|
| Fix `pre-2.0` tag anchor | Bora |
| Release image / RC | Bora |
| Contract-diff + SQ-2 disposition | Bora (with the P6·S1 evidence) |
| Soak window + ingress config | ops |
| olymp repoint + go/no-go + rollback | Bora |
