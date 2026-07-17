{{/*
Shared naming/label/image helpers. Keyed off the CONSUMING chart (.Chart.Name /
.Chart.Version / .Chart.AppVersion), so a module needs no _helpers.tpl of its own —
the library reproduces the exact output the per-module helpers used to emit.
*/}}

{{/* Chart name (overridable via nameOverride). */}}
{{- define "ttr-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name. */}}
{{- define "ttr-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "ttr-service.name" . | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "ttr-service.labels" -}}
app.kubernetes.io/name: {{ include "ttr-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels (stable across upgrades). */}}
{{- define "ttr-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ttr-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved image reference. Tag precedence: per-service `image.tag` (a module override)
→ umbrella-wide `global.image.tag` (the SV-P4 umbrella's single product-tag knob;
nil/absent for a standalone per-service deploy) → the chart's own `appVersion`. The
`global` read is nil-safe so a chart rendered on its own (no umbrella, no `global`)
falls straight through to appVersion — unchanged from before this fallback existed.
*/}}
{{- define "ttr-service.image" -}}
{{- $globalTag := "" -}}
{{- with .Values.global -}}
{{- with .image -}}
{{- $globalTag = .tag | default "" -}}
{{- end -}}
{{- end -}}
{{- $tag := .Values.image.tag | default $globalTag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
Optional pod/container extension hooks. Empty by default; a module OVERRIDES the
one it needs in its own templates/ (the consuming chart's define wins over the
library's). Used for e.g. golem's mounted Shem bundle. The library includes them
in the deployment; an empty override renders nothing (no stray whitespace).
*/}}
{{- define "ttr-service.volumeMounts" -}}{{- end -}}
{{- define "ttr-service.volumes" -}}{{- end -}}
{{/* Optional pod initContainers override (renders the whole `initContainers:` block, or nothing). */}}
{{- define "ttr-service.initContainers" -}}{{- end -}}
