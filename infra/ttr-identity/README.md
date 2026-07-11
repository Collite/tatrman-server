# whois

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`infra/whois/`), tag `kantheon-fork-point`, forked 2026-06-24 (fork Phase 5 Stage 5.1).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The **user/role directory** and **OPA bundle server** — a technical-wave (no-persona) service
in the `infra/` tree. It syncs users + roles from upstream sources (Keycloak admin API, ERP DB)
into its **own Postgres** and serves:

- `GET /whois?internal_id= | ?user_id=&user_id_type= | ?email= | ?id=` → `UserRecord` JSON
  (the shape `whois-common` defines; the one Argos's optional `WhoisRoleSource` consumes — Stage 5.3).
- `GET /bundle/{type}/roles.tar.gz` → an OPA policy bundle (merged `data.json` + `.rego` policies + `.manifest`).
- `GET /health`, `GET /ready` → probes.

Identity discipline (fork §6): whois is **never an identity authority** — it is an optional
*role-enrichment* source. Kantheon resolves identity from the bearer at the theseus-mcp edge;
Argos calls whois (when `argos.roleSource = whois`) only to expand the ERP role hierarchy the
Keycloak token does not carry.

## Package root

`org.tatrman.whois.*` (swept off `infra.whois.*`). Domain records live in the `whois-common`
lib (`org.tatrman.whois.domain`); the Keycloak token provider in the `keycloak-auth` lib
(`org.tatrman.keycloak.auth`) — both forked in Stage 5.0.

## Port

HTTP **7110** (`WHOIS_SERVER_PORT`, kept from ai-platform). No gRPC, no MCP wrapper.

## Repository modes

- `whois.repository.type = json` (default): in-memory from a `whois.json` classpath/file fixture
  — used for local boot and the component tests; no DB needed.
- `whois.repository.type = db`: Postgres-backed (`who-is-database`), Flyway V1–V5 on boot, with
  the Keycloak/ERP sync loop active. The live deployment runs in `db` mode.

## Run

```
just deploy-kt infra/whois          # Jib image + Helm deploy (CI/cluster; Jib build is CI-gated on Rancher)
./gradlew :infra:whois:test         # mocked unit/component suite
```
