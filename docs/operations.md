<!-- SPDX-License-Identifier: Apache-2.0 -->
# Operating Tatrman Server (open tier)

The open Server is operated the way cloud-native infrastructure is operated: through
**configuration and a CLI**, not through a built-in admin application. This is a
deliberate stance (FO-28), not a missing feature — there is no privileged web console
to secure, and no write path an operator can reach that the governed architecture
doesn't already mediate.

> **Operate tier.** On a managed Platform estate the same runtime gains the commercial
> **Studio Operations** perspective — an estate / round / reservation / runs-quota
> console. That is an *operate-tier* add-on; the open tier's operator surface is the
> read-only health page below.

## The health & status surface

The `health` service (image `health:dev`, port `7000`) is the operator's front door.
Every route is **read-only** — the open surface exposes no mutating verb.

| Route | Purpose |
| --- | --- |
| `GET /` | Minimal human status page: Server version, model fingerprint, per-service health. |
| `GET /status` | The same as JSON — `{ serverVersion, modelFingerprint, summary, services[] }`. |
| `GET /health/all` | Liveness rollup across every configured target (`200` healthy / `503` otherwise). |
| `GET /health/{service}` | One target's liveness (e.g. `/health/veles`). |
| `GET /healthz` | The aggregator's own self-probe (use this as the pod liveness probe, not `/health/all`). |

- **Server version** is the single release-train version (`tatrman-server.version`) — the
  whole open Server is built and released together, so there is one version, not a
  per-service matrix.
- **Model fingerprint** is Veles's `model_version` (the loaded model's identity), read
  from `veles /status`. If Veles is unreachable it renders `unavailable` — the page
  never fails because a downstream is down.

Each individual service also serves its own `GET /health`, `GET /ready`, and
`GET /status` (Veles additionally exposes `GET /metrics` in Prometheus format).

## Configuration reference

Every operational knob is an environment variable / Helm value — nothing is set through
a running UI.

**Health surface**

| Variable | Default | Meaning |
| --- | --- | --- |
| `HEALTH_CHECK_SERVICE_PORT` | `7000` | The health service HTTP port. |
| `VELES_STATUS_URL` | `http://veles:7260/status` | Where the status page reads the model fingerprint. |
| `HEALTH_<SERVICE>_URL` | per `application.conf` | Override a target's base URL (e.g. `HEALTH_VELES_URL`). |
| `HEALTH_<SERVICE>_ENDPOINT` | `/health` | Override a target's health path. |
| `HEALTH_CACHE_TTL_SECONDS` | `30` | Result cache TTL for the rollup. |

The full target inventory (every service + infra dependency, each with a `HEALTH_*`
override) lives in `infra/health/src/main/resources/application.conf`; a deploying repo
(olymp) retunes DNS/ports/paths via a ConfigMap without rebuilding the image.

**Per-service wiring** — each service reads its peers from its own `application.conf`
with `${?ENV}` overrides (e.g. `TRANSLATE_SERVER`, `VALIDATE_SERVER`, `VELES_GRPC_HOST`).
See each service's `src/main/resources/application.conf`.

## Running it

The whole Server runs as a Helm release (there is no all-in-one compose file — the
estate is Kubernetes-native):

```bash
# a light query spine + identity/health, for a /health smoke:
helm upgrade --install ttr helm/tatrman-server -f helm/tatrman-server/deploy/values-bp-dsk-smoke.yaml
# then:  curl -fsS http://<health-host>:7000/health/all
```

Golden manifests live under `helm/tatrman-server/deploy/` (`values-bp-dsk-smoke.yaml`,
`values-kind-ci.yaml`) and `helm/tatrman-server/golden/`.

## The "no admin app" stance (FO-28)

The open tier ships **no operator web application** — and this is not a gap:

- **Provisioning & config** is declarative: Helm values + environment variables, under
  version control, applied by your CD (olymp/ArgoCD in the reference estate).
- **Day-2 tasks** (model refresh, cache clear) are **governed cluster-internal verbs**,
  not an operator console. Routes such as `POST /refresh` (query/translate) and
  `POST /admin/reload-stop-words` (Veles, shared-secret gated) are reached *inside the
  cluster* by the platform's own reconciliation, not exposed as an operator admin
  surface. They are intentionally absent from the health page above.
- **There is no write path to your data** on the open operator surface. Data writes,
  where a tier has them at all, go through the governed door — never a side channel.

If you want a graphical operations console (estate overview, planning rounds, reservation
admin, runs & quota), that is the commercial **Studio Operations** perspective on a
Platform estate.

## BI / reporting

Serving data to BI tools is covered separately in **[Superset & BI](superset.md)** — the
open tier is self-serve (semantic layer + bring-your-BI + reference Superset pairing).
