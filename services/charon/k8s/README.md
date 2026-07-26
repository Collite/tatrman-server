```
module: charon
image: ghcr.io/boraperusic/charon     # jib
ports: { http: 7250, grpc: 7251 }
needs:
  pg-database: charon?          # no dedicated DB; talks to named connections (erp-replica, analytics-staging) via charon-db-credentials secret
  seaweed-bucket: charon?       # uses the Seaweed S3 gateway (CHARON_S3_ENDPOINT) — no fixed bucket
  keycloak: {}
  downstream: [ ]               # data mover: Seaweed + Redis + named DB connections (not constellation services)
wave: 1                         # core data-fabric service; agents/services call it
externally-exposed: {}
```

## Notes
- **envFrom:** `charon-db-credentials` secret (`optional: true`) supplies the `${ENV}` credential
  tokens for the named-connection registry. Created by the deploying env, not by this chart.
- **connections volume:** the `charon-connections` ConfigMap (connections.yaml) is mounted read-only
  at `/etc/charon` via `templates/_volumes.tpl`, gated on `connections.configMapName`
  (default `charon-connections`, matching the base). The ConfigMap content is Bora-owned /
  environment-specific and is created by the deploying context — NOT templated here (the base's
  `connections-configmap.yaml` kustomize resource is intentionally not lifted into the chart).
- **storage wiring** (Seaweed S3 endpoint, Redis URL, connections path) lives in `extraEnv` defaults.
- `strategy: Recreate` was not present in the base and is omitted (library default RollingUpdate).
