{{/*
Shared naming/label/image helpers. Keyed off the CONSUMING chart (.Chart.Name /
.Chart.Version / .Chart.AppVersion), so a module needs no _helpers.tpl of its own —
the library reproduces the exact output the per-module helpers used to emit.
*/}}

{{/* Chart name (overridable via nameOverride). */}}
{{- define "tatrman-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name. */}}
{{- define "tatrman-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "tatrman-service.name" . | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "tatrman-service.labels" -}}
app.kubernetes.io/name: {{ include "tatrman-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels (stable across upgrades). */}}
{{- define "tatrman-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tatrman-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved image reference. Tag precedence: per-service `image.tag` (a module override)
→ umbrella-wide `global.image.tag` (the SV-P4 umbrella's single product-tag knob;
nil/absent for a standalone per-service deploy) → the chart's own `appVersion`. The
`global` read is nil-safe so a chart rendered on its own (no umbrella, no `global`)
falls straight through to appVersion — unchanged from before this fallback existed.
*/}}
{{- define "tatrman-service.image" -}}
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
Pod/container volume composition.

⚑ **Helm template names are GLOBAL across an umbrella render, and the last chart loaded
wins.** So a per-chart override of a shared define is only safe while exactly ONE chart in
the render overrides it. `tatrman-service.volumes` used to be an empty hook that charon
overrode wholesale; the moment a second chart needed a volume, one of the two silently lost
— which is precisely what happened when the lexicon mount (RV-P3.3) landed here: chrono,
money, geo and lex-matcher rendered their `*_LEXICON_ARCHIVE_PATH` env var pointing at a
path with **nothing mounted at it**, and the readers would have logged "archive missing"
and served an empty vocabulary. The env var is not the mount, and only a fixture that turns
both on catches the difference.

So the library now OWNS the `volumes:` / `volumeMounts:` keys and composes two contributors:

  1. the **compiled lexicon archive**, implemented here once rather than four times over,
     because four services mount the same artifact under one values contract — OFF unless
     `lexicon.configMapName` is set, so a chart that has never heard of a lexicon renders
     byte-identically to before;
  2. whatever a module adds through `tatrman-service.extraVolumes` /
     `.extraVolumeMounts` — ITEM-level hooks (list entries only, never the parent key), so
     two charts contributing in one render compose instead of one erasing the other.

`initContainers` stays a whole-block hook: nothing here contributes one, so there is no
composition to do and charon's plugin-dir override remains correct as written.

Item indentation belongs to the hook (8 spaces for volumes, 12 for volumeMounts) — the
library emits the key at the right depth and interpolates the items verbatim.
*/}}
{{- define "tatrman-service.extraVolumes" -}}{{- end -}}
{{- define "tatrman-service.extraVolumeMounts" -}}{{- end -}}
{{- define "tatrman-service.lexiconEnabled" -}}
{{- if and .Values.lexicon .Values.lexicon.configMapName }}true{{- end -}}
{{- end -}}
{{/* The in-container path of the mounted archive — the value of every `*_LEXICON_ARCHIVE_PATH`. */}}
{{- define "tatrman-service.lexiconPath" -}}
{{- printf "%s/%s" (.Values.lexicon.mountPath | trimSuffix "/") .Values.lexicon.key -}}
{{- end -}}
{{- define "tatrman-service.volumeMounts" -}}
{{- $extra := include "tatrman-service.extraVolumeMounts" . }}
{{- if or (include "tatrman-service.lexiconEnabled" .) (trim $extra) }}
          volumeMounts:
{{- if include "tatrman-service.lexiconEnabled" . }}
            - name: lexicon
              mountPath: {{ .Values.lexicon.mountPath }}
              readOnly: true
{{- end }}
{{- with $extra }}{{ . }}{{- end }}
{{- end }}
{{- end -}}
{{- define "tatrman-service.volumes" -}}
{{- $extra := include "tatrman-service.extraVolumes" . }}
{{- if or (include "tatrman-service.lexiconEnabled" .) (trim $extra) }}
      volumes:
{{- if include "tatrman-service.lexiconEnabled" . }}
        - name: lexicon
          configMap:
            name: {{ .Values.lexicon.configMapName }}
{{- end }}
{{- with $extra }}{{ . }}{{- end }}
{{- end }}
{{- end -}}
{{/* Optional pod initContainers override (renders the whole `initContainers:` block, or nothing). */}}
{{- define "tatrman-service.initContainers" -}}{{- end -}}
