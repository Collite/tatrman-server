# backstage — Helm chart (the developer portal, Node/Backstage)

Env-agnostic chart for the Kantheon developer portal. **Not** an nginx/Vue FE — Backstage is a
Node backend serving both the app bundle and the backend API on one HTTP port. The chart uses
the kantheon-service library's **backend** Deployment/Service shape + a Gateway API HTTPRoute
for external exposure. Authored in deploy-test WS-D Stage 2 (no pre-existing kustomize base).
**BEST-EFFORT** — renders cleanly; config is env-driven and expected to be overridden per env.

## Deploy descriptor (contracts §2.5)

```
module: backstage
image: ghcr.io/boraperusic/backstage   # node/custom (Backstage build, not Jib/fe-nginx)
ports: { http: 7007 }
needs:
  pg-database: backstage               # app-config.yaml backend.database (client pg)
  keycloak: { client: backstage }      # OIDC production provider (VITE_KEYCLOAK_BACKSTAGE_CLIENT_ID)
  downstream: []                        # portal aggregates the catalog; no runtime service calls
wave: 6                                 # infra
externally-exposed: { hostname: <backstage.…> }   # Olymp sets httpRoute.hostname (default off)
```

## Template choice: BACKEND, not FE

Chosen the **backend** templates (`kantheon-service.deployment` / `.service`) plus
`templates/httproute.yaml` (`kantheon-service.httproute`) — Backstage reads its config from env
(app-config.yaml `${VAR}` substitution), not from a generated `env.js`, so the FE `fe-configmap`
model does not fit. A per-module env hook `templates/_env.tpl` (`backstage.env`) supplies:

- non-secret wiring via `extraEnv` (base URLs, `BACKSTAGE_DB_HOST/PORT/USER/DB`,
  `VITE_KEYCLOAK_BACKSTAGE_CLIENT_ID`, `VITE_KEYCLOAK_URL_METADATA`), and
- secrets via `secretKeyRef` (`optional: true`): `BACKSTAGE_DB_PASSWORD`,
  `BACKSTAGE_SESSION_SECRET`, `KEYCLOAK_CLIENT_SECRET`.

The backend listen port (**7007**) is hardcoded in `app-config.yaml` (`backend.listen.port`), so
no port env is emitted. No OTel env — Backstage does not consume the `OTEL_ENABLED_*` idiom
(`telemetry.serviceName` is kept for parity only).

## Shape & notes

- Single HTTP port 7007 (library omits grpc). Service `port: 7007` feeds the HTTPRoute backendRef.
- **Probes** hit `/healthcheck` (Backstage's built-in health endpoint).
- **Exposure.** `httpRoute.enabled` (default false — Olymp sets it) attaches a Gateway API HTTPRoute.
- **strategy** intentionally omitted (D2 convention).

## Uncertainty (best-effort)

- Image is a **Node/Backstage** build (`node/custom`), not Jib or fe-nginx — the D2 image recipes
  don't cover it; a Backstage `Dockerfile`/`yarn build` image is assumed.
- The many `${VAR}` app-config keys default to in-cluster names + optional Secrets; a real deploy
  must provision `backstage-db-credentials` / `backstage-secrets` and the correct base URLs.

## Image

node/custom — Backstage build (not Jib/fe-nginx).
