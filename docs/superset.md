<!-- SPDX-License-Identifier: Apache-2.0 -->
# Superset & BI

Tatrman's stance on BI is **bring-your-own** (FO-6): the open Server publishes a
governed **semantic layer**, and any BI tool reads through it. Superset is the
reference pairing — fully self-serve on the open tier, and available as a managed
**operated add-on** on Platform (operate-tier) estates.

The dividing line (FO-26):

| | Open tier (Apache-2.0) | Operate tier (Platform) |
| --- | --- | --- |
| Superset | **self-serve** — you run it, we document the pairing | **operated add-on** — we run it, managed |
| Governance | semantic layer + RLS on every query | same, plus estate-managed guest-token RLS |
| Promise | batteries-included, self-hosted | managed service SLA |

---

## Open tier — self-serve (FO-6)

Everything a team needs to point their own Superset (or any SQL-speaking BI tool) at
governed data, self-hosted.

### The semantic layer

The Server exposes modeled entities — not physical tables — through the governed query
path (translate → validate → dispatch). Row-level security is injected *into the plan*,
so a BI query cannot see rows the caller isn't entitled to, regardless of how the
dashboard was authored. This is the same governed path the MCP surface uses; BI is just
another consumer of it.

### Pairing Superset

1. **Connect** Superset to the semantic layer as a database source (governed endpoint,
   not a raw warehouse connection — that is the whole point).
2. **Authenticate** with a per-user token so RLS resolves to the viewing user.
3. **Build** datasets/charts against modeled entities; provenance rides every result.

### Compose convenience & embedding

- A compose-convenience bundle for a local Superset alongside a dev Server is provided
  for evaluation (self-serve; not a production topology).
- **Embedding** dashboards into your own app uses Superset's embedded SDK against your
  self-hosted instance. For governed embedding with per-viewer RLS, see the guest-token
  wiring below (the same mechanism the operated add-on manages for you).

Keep these docs current against the **shipped** semantic layer (the FO-6 capability
ladder): as new entity/query capabilities land, the pairing/embed examples track them.

---

## Operate tier — operated Superset add-on (FO-26)

> **This is an operate-tier managed add-on, not an open-tier promise.** On an open
> self-hosted Server you run and maintain Superset yourself (above). The following is the
> runbook for the *operated* instance we manage on a Platform estate.

### Provisioning

- Superset is deployed as a managed workload on the estate (Helm/GitOps via olymp),
  wired to the estate's semantic-layer endpoint and identity provider (the same Keycloak
  the rest of the estate uses — one login).
- Datasets are pre-bound to the estate's modeled entities; no raw warehouse credentials
  are ever handed to Superset.

### Guest-token RLS wiring (PF B)

Embedded, per-viewer dashboards use **Superset guest tokens** carrying the viewer's
**row-level-security context**:

- The estate mints a short-lived guest token per viewer, stamped with the viewer's RLS
  predicates (derived from their entitlements — the same predicates the governed query
  path injects).
- Superset applies them as RLS rules on the embedded dashboard, so an embedded viewer
  sees exactly their entitled rows — enforced by the governed layer, not by dashboard
  authoring discipline.
- Tokens are short-lived and minted server-side; a dashboard embed never carries a
  long-lived credential.

### Upgrade & backup

- **Upgrades** follow the estate's GitOps flow: bump the managed Superset chart version,
  reconcile, verify against a smoke dashboard; metadata migrations run as part of the
  managed upgrade.
- **Backup**: Superset's metadata database is backed up on the estate's standard schedule;
  dashboards/datasets are also exportable as version-controllable assets. Governed data
  itself is never copied into Superset — it stays in the warehouse, read live through the
  semantic layer.

---

*See also:* **[operations.md](operations.md)** (operating the open Server) · the FO-6
capability ladder and the semantic-layer contracts in the companion `tatrman` repo.
