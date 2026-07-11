# `ttr-service` — shared Helm library chart

The common Deployment/Service shape every constellation module ships, extracted
once so a probe/label/scheduling fix lands in a single place. Introduced by the
deploy-test program, Workstream D Stage 1 (`docs/implementation/v1/deploy-test/tasks-d1-chart-library.md`),
per contract `docs/architecture/deploy-test/contracts.md` §1.

`type: library` — it emits **no** resources on its own. Each deployable's thin
`<module>/k8s` chart declares it as a dependency and renders its named templates.

## What a consuming chart looks like

A migrated module keeps its `<module>/k8s` directory (so olymp's
`chartPath: <module>/k8s` deploy convention is unchanged) but is now thin:

```
<module>/k8s/
├── Chart.yaml            # type: application + a dependency on ttr-service
├── values.yaml           # the module's defaults (image, ports, resources, env inputs)
└── templates/
    ├── deployment.yaml   # {{ include "ttr-service.deployment" . }}
    ├── service.yaml      # {{ include "ttr-service.service" . }}
    └── _env.tpl          # define "<Chart.Name>.env" — the one per-module surface
```

`Chart.yaml` dependency stanza (relative `file://` path; every module sits at
`<top>/<module>/k8s`, i.e. three levels above the repo root):

```yaml
dependencies:
  - name: ttr-service
    version: 0.1.0
    repository: "file://../../../shared/charts/ttr-service"
```

## Named templates

| Template | Emits |
|---|---|
| `ttr-service.deployment` | backend Deployment (Kotlin/Ktor · agent · worker · Python) |
| `ttr-service.service` | backend ClusterIP Service (`http`; `grpc` gated on `.Values.ports.grpc`) |
| `ttr-service.helpers` (`.name`/`.fullname`/`.labels`/`.selectorLabels`/`.image`) | naming + labels, keyed off `.Chart.*` |
| `ttr-service.fe-deployment` / `.fe-service` / `.fe-configmap` / `.httproute` | FE-nginx shape (see `frontends/iris/k8s`) |

## The per-module `env` hook

The container `env:` block is the one genuinely per-service surface (its var
names, OTel wiring, DB/secret env), so it stays with the module as a named
template the library includes:

```gotemplate
{{- define "<Chart.Name>.env" -}}
- name: FOO_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
# … lifted at 0-base indentation; the library applies `nindent 12` …
{{- end -}}
```

`.Chart.Name` is the chart's `name:` (e.g. `themis`'s chart is `themis-mcp`, so
its hook is `themis-mcp.env`). Author the body at **column 0** (`- name:` flush
left); the library indents it under `env:`.

## Optional pod hooks

`ttr-service.volumeMounts` and `ttr-service.volumes` default to empty.
A module that needs extra mounts (e.g. golem's Shem bundle, `agents/golem/k8s/templates/_volumes.tpl`)
**overrides** them — the consuming chart's `define` wins over the library's.

## Rendering: `helm dependency build`

`file://` dependencies must be materialised before `helm template`:

```sh
helm dependency build <module>/k8s     # vendors the library into charts/ (gitignored)
helm template <name> <module>/k8s
```

`just validate-charts` does this for every chart and diffs the render against the
goldens in `shared/charts/.golden/` (the CI gate — a drifted render fails).

**ArgoCD note (olymp / WS-D3):** ArgoCD runs `helm dependency build` during
manifest generation, and because the library lives in the same kantheon repo the
relative `file://` path resolves at render time (single checkout). The vendored
`charts/*.tgz` + `Chart.lock` are build artifacts — gitignored, rebuilt on demand.
