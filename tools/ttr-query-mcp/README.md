# ttr-query-mcp

> **forked-from:** `ai-platform@e6ee38c77541dd8c2b7a31af3fbec8d4c008dabc` (`tools/query-mcp/`, ai-platform HEAD), forked 2026-06-15 (Stage 3.5).
> *Forked from HEAD, not the `kantheon-fork-point` tag:* query-mcp saw a post-fork-point, proto-compatible "pattern query parametrization" improvement (Conversions + tests) worth carrying; `shared/proto/.../plan` was unchanged between the tag and HEAD, so there is no proto drift.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

**ttr-query-mcp** — the agent-facing MCP edge for **query-runner**. Exposes the
`query` (run_query) and `compile` tools over StreamableHTTP, turns Arrow IPC
results into JSON/CSV/TSV/Markdown rows, and resolves caller identity into the
`PipelineContext` that flows through the whole chain.

## IdentityResolver + the OBO gate (the sensitive bit)

`identity/IdentityResolver` validates the Keycloak JWT shape and extracts
`user_id` (`preferred_username` → `sub`) + roles from `realm_access.roles`
(forked **unchanged**). `identity/IdentityGate` is the fail-closed decision
(kantheon-security §2/§2.1), unit-tested:

- valid user bearer → identity with `user_id` + `auth_roles`;
- **no identity + `require-identity`** → rejected `missing_user_identity`;
- **service-account token with no user claim** → rejected — agents must call with
  the user's OBO token, never service identity;
- token-vs-`user_id` arg conflict → rejected `identity_conflict` (no spoofing).

The resolved roles become `PipelineContext.auth_roles` and reach ttr-validate
unchanged (bearer-roles, contracts §3) — verified end-to-end by
`RunQueryFullChainComponentSpec` (run_query → real in-process query-runner → mocked
ttr-translate/ttr-validate/ttr-dispatch/worker → Arrow → JSON rows).

## Tools + registration

The tool vocabulary forks as-is (contracts §2): `query` / `compile`. Their
`ToolCapability` manifests live in `resources/manifests/tools/`
(`query.run:v1`, `query.compile:v1`) and register with capabilities-mcp at
startup (warn-and-continue; opt in with `CAPABILITIES_MCP_URL`).

## Status (Stage 3.5)

Port: HTTP 7307 (MCP `/mcp` + `/health` + `/ready`). Upstreams: query-runner (gRPC
7306) + ttr-translate/ttr-validate/Veles for compile/decoration. Tag: `ttr-query-mcp/v0.1.0`.
