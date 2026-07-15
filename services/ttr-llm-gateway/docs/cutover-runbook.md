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
| Cutover = **image tag + a 2.0 env/probe rewire** (same Service + chart, new values) | the shipped chart is 1.x Spring-shaped — actuator probes, `POSTGRESQL_*`/`SERVER_PORT` env, `secretEnv` = 1.x key names, no Redis/governance. 2.0 (Ktor) reads `LLM_GATEWAY_*` + serves `/health/*`, so the flip needs the §2b values, not just `image.tag` (verified: a bare tag bump left 2.0 storeless — Redis disabled) |
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
3. **Flush the Redis cache (G-1) — only after Redis is wired (§2b).** 2.0's `CacheEnvelope` format is
   new. The `data`-ns Redis is **shared with Charon**, so do NOT `FLUSHDB`; delete only the gateway's
   cache namespace (`llm-gateway:chat:*`; the rate-limiter's `llm-gateway:rl:*` and Charon's keys stay
   intact):
   ```bash
   PW=$(kubectl -n data get secret redis-auth -o jsonpath='{.data.password}' | base64 -d)
   kubectl -n data exec deploy/redis -- env REDISCLI_AUTH="$PW" sh -c \
     "redis-cli --scan --pattern 'llm-gateway:chat:*' | xargs -r redis-cli UNLINK"
   ```
   (`FLUSHDB` is fine ONLY for a dedicated gateway Redis, e.g. the staging `llmgw-redis`.)
4. **Deploy 2.0 per §2b — NOT a bare image-tag bump — one cluster at a time**, then apply the consumer
   flip (§2a) in the same sync. The shipped chart is Spring-1.x-shaped (actuator probes, `POSTGRESQL_*`
   env, no Redis/governance); a tag bump alone leaves 2.0 **storeless with probes it doesn't serve**
   (this is why Redis reads as disabled after a plain bump). Do `clusters/collite-o1/…` first, watch
   (§4), then `clusters/bp-dsk/…`. The migrated consumers speak 2.0's key-gated `/v1` with no 1.x
   fallback, so the gateway flip and the consumer flip must land together.
5. **Removals** (only after SQ-2 sign-off): gRPC + protos, `/api/v1` (post-Kleio `/v1`), async jobs +
   NATS — land as follow-ups, not inside the repoint.

## 2b. Wire the 2.0 gateway deployment (olymp `apps/llm-gateway/values.yaml`)

⚠ The shipped chart (`services/ttr-llm-gateway/k8s` + shared `ttr-service`) is the **1.x Spring**
chart: probes hit `/actuator/health/*`, env renders `POSTGRESQL_*`/`SERVER_PORT`, `secretEnv` names the
1.x provider keys, and nothing wires Redis or governance. The Ktor 2.0 image ignores all of that and
reads a different surface, so the flip needs values changes, not just `image.tag`. Values shown for
**bp-dsk** (collite-o1 identical bar hostnames).

**i. Distribute the DB + Redis creds into `ttr-server`** — an env `secretKeyRef` cannot cross
namespaces, and both creds live in `data`. Add two ClusterExternalSecrets in
`clusters/<cluster>/platform/auth/` (mirror `clusterexternalsecret-ghcr-pull.yaml`, whose
`namespaceSelectors` already include `ttr-server`) so ESO materializes, in `ttr-server`:
`pg-llm-gateway-cred` (vault key `pg-llm-gateway` → `password`) and `redis-auth` (vault key
`redis-auth` → `password`).

**ii. `apps/llm-gateway/values.yaml`** — drop the 1.x `extraEnv: SPRING_PROFILES_ACTIVE=test`, then:

```yaml
image:
  tag: "2.0.0"
  pullPolicy: IfNotPresent            # immutable release tag

# Ktor health endpoints — NOT Spring actuator. The chart defaults (/actuator/health/*) 404 on 2.0,
# so the pod never goes Ready. 2.0 is a fast starter; the long Spring startup budget can shrink.
startupProbe:   { path: /health/ready, initialDelaySeconds: 5,  periodSeconds: 5,  failureThreshold: 30 }
readinessProbe: { path: /health/ready, initialDelaySeconds: 5,  periodSeconds: 5,  failureThreshold: 10 }
livenessProbe:  { path: /health/live,  initialDelaySeconds: 20, periodSeconds: 15 }

# 2.0 provider-key env names (providers.conf `keyEnv`). NOTE azure = AZURE_OPENAI_KEY, not the 1.x
# AZURE_OPENAI_API_KEY. optional:true so a keyless / WireMock bring-up still boots.
secretEnv:
  - { name: AZURE_OPENAI_KEY,  secretName: llm-gateway-secrets, secretKey: azure-openai-key,   optional: true }
  - { name: ANTHROPIC_API_KEY, secretName: llm-gateway-secrets, secretKey: anthropic-api-key,  optional: true }
  - { name: OPENAI_API_KEY,    secretName: llm-gateway-secrets, secretKey: openai-api-key,      optional: true }
  - { name: GEMINI_API_KEY,    secretName: llm-gateway-secrets, secretKey: gemini-api-key,      optional: true }

extraEnv:
  # Postgres — prompt-logs, governance/keys, budgets. NAME/USER are the CNPG db + owner (`llm_gateway`),
  # NOT the app defaults (`llmgateway`/`tatrman`). The #11 fix grants to db.user → a self-grant here.
  - { name: LLM_GATEWAY_DB_ENABLED, value: "true" }
  - { name: LLM_GATEWAY_DB_HOST,    value: "postgres-rw.data.svc.cluster.local" }
  - { name: LLM_GATEWAY_DB_PORT,    value: "5432" }
  - { name: LLM_GATEWAY_DB_NAME,    value: "llm_gateway" }
  - { name: LLM_GATEWAY_DB_USER,    value: "llm_gateway" }
  - name: LLM_GATEWAY_DB_PASSWORD
    valueFrom: { secretKeyRef: { name: pg-llm-gateway-cred, key: password } }
  # Redis — cache + rate-limit. The #10 fix adds the password support this needs.
  - { name: LLM_GATEWAY_REDIS_ENABLED, value: "true" }
  - { name: LLM_GATEWAY_REDIS_HOST,    value: "redis.data.svc.cluster.local" }
  - { name: LLM_GATEWAY_REDIS_PORT,    value: "6379" }
  - name: LLM_GATEWAY_REDIS_PASSWORD
    valueFrom: { secretKeyRef: { name: redis-auth, key: password } }
  - { name: OTEL_ENABLED_LLM_GATEWAY, value: "true" }   # optional
```

**iii. Verify after the roll:** `kubectl -n ttr-server exec deploy/llm-gateway -- wget -qO- localhost:7280/health/ready`
returns `200` with PG+Redis probed; `kubectl -n ttr-server logs deploy/llm-gateway | grep -i flyway`
shows V1–V3 applied. **iv. Seed the key hashes → §2a step 2** (needs the DB from ii).

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

2. **Seed the SHA-256 hashes into the gateway** (the `sha256=` values from step 1). Needs the DB from
   §2b — with the DB up, `module()` uses `PgKeyValidator`, which validates a presented `ttrk-` key by
   `sha256Hex(key)` against `virtual_keys` (a row that exists and is not revoked ⇒ the team's principal).
   `governance.yaml` ships `keys: []`, so the hashes must be put in one of two places — pick one:

   **(a) Direct SQL — no chart change, do it once after §2b is green.** The six teams are upserted into
   `teams` at boot from the baked `governance.yaml`, so only the key rows are needed. `key_hash` is the
   SHA-256 hex (not a credential — irreversible; the plaintext stays in the consumer secrets):

   ```bash
   # psql into the CNPG primary, e.g.:  kubectl -n data exec -it postgres-1 -- psql -U llm_gateway -d llm_gateway
   INSERT INTO virtual_keys (id, team_id, name, key_hash, seeded) VALUES
     ('vk_kleio_cutover',       'kleio',       'kleio-cutover',       '<sha256-kleio>',       true),
     ('vk_kallimachos_cutover', 'kallimachos', 'kallimachos-cutover', '<sha256-kallimachos>', true),
     ('vk_pinakes_cutover',     'pinakes',     'pinakes-cutover',     '<sha256-pinakes>',     true),
     ('vk_hebe_cutover',        'hebe',        'hebe-cutover',        '<sha256-hebe>',        true)
   ON CONFLICT (key_hash) DO NOTHING;
   ```

   The positive-validator cache TTL is ≤30 s (a new key is live within 30 s). Revoke with
   `UPDATE virtual_keys SET revoked_at = now() WHERE id = 'vk_<team>_cutover';`.

   **(b) Governance ConfigMap override — the designed path (survives a DB wipe; re-imported idempotently
   at boot by `GovernanceLoad`).** Mount a populated `governance.yaml` over the classpath copy. The
   library chart's volume hooks are empty by default, so add them once (`services/ttr-llm-gateway/k8s/
   templates/_volumes.tpl`):

   ```yaml
   {{- define "ttr-service.volumeMounts" -}}
   - { name: governance, mountPath: /app/resources/governance.yaml, subPath: governance.yaml }
   {{- end -}}
   {{- define "ttr-service.volumes" -}}
   - { name: governance, configMap: { name: llm-gateway-governance } }
   {{- end -}}
   ```
   ```yaml
   # ConfigMap in ttr-server (hashes are not credentials → a ConfigMap is fine; plaintext never here).
   apiVersion: v1
   kind: ConfigMap
   metadata: { name: llm-gateway-governance, namespace: ttr-server }
   data:
     governance.yaml: |
       teams:            # the 6 teams verbatim from the image's governance.yaml
         - { id: golem,  costCenterPrefix: "golem/",  budget: { id: golem-monthly,  usdPerMonth: 100.0, mode: soft }, rateLimit: { id: golem-rl,  requestsPerMinute: 120 } }
         # … hebe, themis, kleio, kallimachos, pinakes …
       keys:
         - { team: kleio,       name: kleio-cutover,       sha256: <sha256-kleio> }
         - { team: kallimachos, name: kallimachos-cutover, sha256: <sha256-kallimachos> }
         - { team: pinakes,     name: pinakes-cutover,     sha256: <sha256-pinakes> }
         - { team: hebe,        name: hebe-cutover,        sha256: <sha256-hebe> }
   ```

3. **Wire the consumer env + key secrets** — done for bp-dsk on olymp branch `lg-p6-consumer-cutover`
   (mirror it for collite-o1). Two delivery styles:

   - **kleio / kallimachos / pinakes** — a `ClusterExternalSecret` per consumer
     (`clusters/<cluster>/platform/auth/clusterexternalsecret-<c>-llm-gateway-key.yaml`) pulls
     `ttrk-<team>` from `azure-store` into a `<c>-llm-gateway-key` Secret in the kantheon ns, and
     `apps/<c>/values.yaml extraEnv` gets:
     ```yaml
     - { name: <C>_LLM_GATEWAY_HOST, value: "llm-gateway.ttr-server" }
     - { name: <C>_LLM_GATEWAY_PORT, value: "7280" }
     - name: <C>_LLM_GATEWAY_KEY
       valueFrom: { secretKeyRef: { name: <c>-llm-gateway-key, key: key } }
     ```
     (`<C>` = `KLEIO`/`KALLIMACHOS`/`PINAKES`; register each ExternalSecret in the auth
     `kustomization.yaml`. pinakes keeps its `sonnet` default in app config.)
   - **hebe** — no ExternalSecret: put the `ttrk-hebe` plaintext in the `hebe-dev` provisioning secret
     under key `llm-gateway-key` (via `just hebe-provision dev`). `apps/hebe/values.yaml` toml sets
     `base_url = "http://llm-gateway.ttr-server:7280/v1"` and `embedding_model = "ada-002"`.

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
