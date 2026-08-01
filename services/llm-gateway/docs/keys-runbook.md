# LLM Gateway — virtual key runbook (LG-P4·S3)

Operating manual for issuing, listing, revoking, and rotating `ttrk-` virtual keys via the
Keycloak-gated admin API (`/admin/keys`, contracts §1.8). The data plane (`/v1/*`) validates these keys
itself (D-1); the admin plane is gated by a Keycloak JWT carrying the realm role **`llm-gateway-admin`**.

> Prerequisites: the gateway is booted with `db.enabled=true` and an `admin { … }` block whose
> `realmPublicKey` is the realm's RS256 public key (Keycloak → *Realm Settings → Keys* → RS256 →
> *Public key*, the base64 blob). Env: `LLM_GATEWAY_ADMIN_ENABLED=true`,
> `LLM_GATEWAY_ADMIN_ISSUER`, `LLM_GATEWAY_ADMIN_AUDIENCE`, `LLM_GATEWAY_ADMIN_REALM_PUBLIC_KEY`.
> `$GW` below is the gateway base URL (e.g. `http://localhost:7280`).

## 0. Obtain an admin token

A service account or user with the `llm-gateway-admin` realm role, via the client-credentials grant:

```bash
export KC=https://keycloak.example/realms/tatrman
export TOKEN=$(curl -s -X POST "$KC/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=llm-gateway-admin-cli \
  -d client_secret="$KC_CLIENT_SECRET" | jq -r .access_token)
```

The gateway verifies the token's signature, issuer, audience, expiry, and the `llm-gateway-admin` realm
role in-service — an Envoy-injected identity header is ignored here (defense in depth, D-1/DQ-1).

## 1. Issue a key

```bash
curl -s -X POST "$GW/admin/keys" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"team":"golem","name":"golem-prod"}'
```

`201 Created`:

```json
{ "id": "vk_01J…", "key": "ttrk-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
  "team": "golem", "name": "golem-prod", "created_at": "2026-07-15T…Z" }
```

**The `key` (plaintext) is shown exactly once** — capture it now; only its SHA-256 hash is stored.
Hand it to the consumer as its `Authorization: Bearer ttrk-…`. Unknown team → `400`.

## 2. List keys

```bash
curl -s "$GW/admin/keys?team=golem" -H "Authorization: Bearer $TOKEN"   # omit ?team= for all teams
```

```json
{ "data": [ { "id":"vk_01J…", "team":"golem", "name":"golem-prod",
             "created_at":"…", "revoked_at":null, "last_used_at":"…" } ] }
```

The list never contains the plaintext or the hash.

## 3. Revoke a key (emergency — a leaked key)

```bash
curl -s -X DELETE "$GW/admin/keys/vk_01J…" -H "Authorization: Bearer $TOKEN" -o /dev/null -w '%{http_code}\n'
# → 204
```

Revocation takes effect on the **next** data-plane request, and always **within 30 s** (the validator's
positive-cache TTL, contracts §1.8). Revoking is idempotent (a second DELETE is still `204`).

## 4. Rotate a team onto a fresh key (zero-downtime)

1. Issue the new key (step 1) and roll it out to the consumer.
2. Confirm traffic is flowing on the new key: `GET /admin/keys?team=<team>` and watch `last_used_at`
   advance on the new row.
3. Revoke the old key (step 3). If the consumer is still on the old key it will start getting `401`
   within 30 s — the signal to complete the rollout.

## 5. Verify a key works on the data plane

```bash
curl -s "$GW/v1/models" -H "Authorization: Bearer ttrk-…" -o /dev/null -w '%{http_code}\n'   # 200 = valid, 401 = unknown/revoked
```

## Attribution headers (data plane, contracts §1.2)

- `X-Cost-Center: <team>/<sub>` — refines cost attribution **within** the key's team; prefix-validated,
  so a `golem` key sending `X-Cost-Center: hebe/…` is rejected `400`. Absent ⇒ the team default.
- `X-Turn-Ref: <id>` — trace-only; logged, never a metric label.
- `traceparent` — W3C trace context, continued.

> ⚑ This runbook's commands were validated against the component-test stack (`AdminApiSpec`); the live
> Keycloak token step (§0) depends on the `llm-gateway-admin` realm role existing (LGQ-2, Bora).
