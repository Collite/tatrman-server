# LLM Gateway 1.x → 2.0 — cutover runbook

> Scope: repoint the deployed `ttr-llm-gateway` from the Spring 1.x image to the Ktor 2.0 image.
> Authored in LG-P6·S1·T6. The GATE that authorizes running this runbook is LG-P6·S2 — do not
> execute until every pre-flight below is green.
>
> ⚠ **The "zero caller changes" premise is RETIRED (P6·S1 soak, kantheon#16).** 2.0's `/v1` is
> key-gated where 1.x `/api/v1` was permit-all, and 2.0 dropped the 1.x-only URL/shape variants.
> So the constellation consumers that call the gateway on the chat/embeddings path **were migrated
> and now require a ttrk- key**: kleio (`/v1/chat/completions`), kallimachos (`/v1/embeddings`),
> pinakes (`/v1/chat/completions`) — kantheon `5af7361` (URL/shape) + `504d60a` (auth slice). Their
> repoint + key wiring is now a **coupled cutover step** (§2a), not a no-op. Golems already target
> `llm-gateway.ttr-server:7280`.
>
> Owners: **Bora** (release + olymp repoint + key seed + go/no-go) · ops (soak window, ingress).

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
- [ ] **Consumers migrated + key-ready** (P6·S1 soak, kantheon#16). The kleio/kallimachos/pinakes
      images in the estate are built from kantheon `504d60a`+ (URL/shape migration + `Authorization:
      Bearer` from `*_LLM_GATEWAY_KEY`); **hebe** was already 2.0-native (openai-compat GatewayClient
      with Bearer + `X-Cost-Center`, k8s profile `llm.source = gateway`) — config-only switch. Governance
      seeds a team per consumer (`kleio`/`kallimachos`/`pinakes`/`hebe` — governance.yaml). Per-team
      ttrk- keys minted, their SHA-256 seeded into the gateway's governance config, and the plaintext in
      Azure KV (kleio/kallimachos/pinakes) or the `hebe-dev` secret (hebe). Verified once on staging: a
      real consumer binary authenticates (a 401 can't settle a prompt-log row).

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
   `clusters/bp-dsk/apps/llm-gateway/values.yaml`. **Apply the consumer repoint (§2a) for the same
   cluster in the same sync** — the migrated consumers speak 2.0's key-gated `/v1` and cannot fall
   back to the 1.x gateway, so the gateway flip and the consumer flip must land together.
5. **Removals** (only after SQ-2 sign-off): gRPC + protos, `/api/v1` (post-Kleio `/v1`), async jobs +
   NATS — land as follow-ups, not inside the repoint.

## 2a. Consumer repoint + key seeding (G-3 — coupled with §2 step 4)

Every consuming service must (a) point its gateway host at the 2.0 Service and (b) present a ttrk- key.
The gateway Service is `llm-gateway` in ns `ttr-server`, port `7280` (the golems already use it); the
legacy `prometheus` host value is retired. Four callers on this path, in two key-delivery styles:

- **kleio, kallimachos, pinakes** — env-configured (`*_LLM_GATEWAY_HOST/PORT/KEY`); the key rides a
  per-consumer **ClusterExternalSecret** from the vault into the kantheon ns (olymp branch
  `lg-p6-consumer-cutover`).
- **hebe** — toml-configured (`[llm] base_url`, `api_key_secret`); already 2.0-native (k8s profile =
  `llm.source = gateway`, so it sends Bearer + `X-Cost-Center hebe/<instance>`). Its key is the
  `llm-gateway-key` field of the **`hebe-dev` provisioning secret** (`just hebe-provision dev`), not a
  ClusterExternalSecret. Its `base_url` gains the `/v1` segment and `embedding_model` moves to `ada-002`
  (the only served embedding model; 1536-dim, no vector migration). Golems already target the 2.0
  Service and are unaffected.

1. **Mint one ttrk- key per consumer team** (never committed — plaintext lives only in Azure KV; hebe's
   goes into `hebe-dev` via provision.sh instead). Teams `kleio`/`kallimachos`/`pinakes`/`hebe` all
   exist in governance.yaml:

   ```bash
   # 256-bit url-safe secret with the ttrk- prefix; print the key and its SHA-256 (the seed hash).
   for team in kleio kallimachos pinakes hebe; do
     key="ttrk-$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
     hash="$(printf '%s' "$key" | shasum -a 256 | cut -d' ' -f1)"
     echo "$team  key=$key  sha256=$hash"
     # kleio/kallimachos/pinakes → vault; hebe's key goes into the hebe-dev secret via provision.sh.
     [ "$team" = hebe ] || az keyvault secret set --vault-name <kv> --name "ttrk-$team" --value "$key" >/dev/null
   done
   ```

2. **Seed the SHA-256 hashes** into the gateway's governance `keys:` for the cutover deploy (the
   `keys:` list stays empty in git — seed via the deploy's governance override / secret, mirroring the
   P6·S1 staging ConfigMap). One entry per team: `{ team: kleio, name: kleio-cutover, sha256: <hash> }`.

3. **Wire the consumer ExternalSecrets + env** (olymp — see the `lg-p6-consumer-cutover` branch):
   each consumer gets an ExternalSecret pulling `ttrk-<team>` from the `azure-store` into a k8s Secret,
   and its values gains `*_LLM_GATEWAY_HOST=llm-gateway.ttr-server`, `*_LLM_GATEWAY_PORT=7280`, and
   `*_LLM_GATEWAY_KEY` via `secretKeyRef`. pinakes also carries `PINAKES_LLM_GATEWAY_MODEL` (sonnet).

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
| Mint ttrk- keys + Azure KV load + governance seed (§2a) | Bora |
| olymp gateway + consumer repoint (§2 step 4 / §2a) + go/no-go + rollback | Bora |
