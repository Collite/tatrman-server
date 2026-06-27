# argos

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/validator/`), tag `kantheon-fork-point`, forked 2026-06-14 (Stage 3.1).
> **also forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/sql-security/`), the policy engine folded in-process (Stage 3.2, 2026-06-14).
> Maintained independently since the fork; do not assume parity with the ai-platform originals.

**Argos** — the validator for kantheon, the single mandatory checkpoint between
"what the user wanted" and "what runs against data". It receives a physical
`PlanNode` (post-Proteus MAP_TO_PHYSICAL) and:

- **SECURITY** — applies row-level security predicates (wraps matching `TableScan`s
  with `Filter`s) and column rules (DENY → reject, MASK → rewrite).
- **RULES** — enforces TopN (a `LimitOffset` cap) and the DF-V01 column allow/deny set.
- **STRICT COERCION** (DF-V06, opt-in) — rejects implicit type-widening the DB would silently coerce.
- **LLM GUARD** (DF-V04, opt-in) — an LLM-as-judge semantic review with configurable fail-open/closed posture.
- **ADMIN BYPASS** (DF-V02) — `apply_security = false` honoured only when the caller's
  `PipelineContext.auth_roles` carries the configured admin role.

Output is the augmented `PlanNode` plus a `SecurityRuleApplied` audit list.

## Identity / roles (the fork's sensitive bit)

Argos sources roles from **`PipelineContext.auth_roles`**, populated upstream at the
theseus-mcp edge from the JWT's `realm_access.roles`. Argos **trusts** the context —
it does **not** parse or re-verify the bearer (in-cluster trust model; zero-trust
hop re-validation is deferred, contracts §3 / `kantheon-v1.1.md` §1). Since the Stage 3.2
fold, the in-process policy engine keys row/column policies on these bearer roles
(default `roleSource = bearer`). There is **no whois** anywhere in Argos — sql-security's
whois client was dropped in the fold; the optional whois *enrichment* source is a Phase-5
additive.

## Policy engine (in-process since Stage 3.2)

Row-level predicates + column rules are evaluated **in-process** by `policy/PolicyEngine`
(folded from `infra/sql-security`; the gRPC `SecurityService`, the legacy SQL-fragment
endpoint, and OPA were dropped). `SecurityApplier` calls it through the in-process
`LocalPolicyClient` — no network hop.

**Policy store (HOCON, DF-S01).** Policies live in
[`src/main/resources/policies/policies.conf`](src/main/resources/policies/policies.conf)
(`argos.policies = [...]`), included by `application.conf`. **Authoring workflow:** edit
that file, commit, redeploy — policies are version-controlled, not a runtime API. For a
per-environment override without a rebuild, mount a HOCON file into the pod and set
`ARGOS_POLICIES_FILE` to its path; its `argos.policies` replaces the built-in set.

**Performance.** In-process evaluation is microsecond-scale (no gRPC round-trip). The
`PolicyLatencySpec` guard asserts p50 well under **2 ms** for a single-table plan against
the default policy set — comfortably below the in-cluster network hop the fold removed.

## Proto

The service proto is `org.tatrman.argos.v1` / `ArgosService` (RPC `Validate` kept;
`cz.dfpartner.validator.v1` is gone). `org.tatrman.security.v1` is now a **data contract**
only (`EvaluatePoliciesRequest`/`Response`, `TablePredicate`, `ColumnRule`) — the in-process
DTOs the `PolicyEngine` consumes/produces. Its gRPC `SecurityService`, legacy SQL-fragment
messages, and `GetStatus` were removed in the 3.2 fold (the surface is not re-exposed).

## Run

```bash
just build-kt argos           # compile
just test-kt argos            # Kotest unit + component suite (98 tests)
just deploy-kt argos          # Jib image + k8s/overlays/local (local K3s)
```

Role source is configurable: `argos.roleSource = bearer` (default; roles from the
forwarded OBO bearer) | `whois` (opt-in ERP role-enrichment, fork Phase 5).

## Status (Stage 3.2 — Argos whole)

Validator core + the in-process policy engine in one pod, both forked suites green
(98 tests). Readiness gates on **Ariadne** only (the policy engine is in-process — no
sql-security hop). Ports: HTTP 7285 (health/ready/status) · gRPC 7286 (`Validate`).
Tag: `argos/v0.1.0`.
