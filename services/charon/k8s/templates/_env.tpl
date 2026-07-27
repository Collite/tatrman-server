{{/* charon container env — HTTP/GRPC ports + OTel + downstream/storage extraEnv. */}}
{{- define "charon.env" -}}
- name: CHARON_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: CHARON_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_CHARON
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- /* Secret-resolver plugin (CH-D8 / CH-P3 T2b) — only when an adapter image is mounted. */ -}}
{{- with .Values.pluginDir }}
{{- if .image }}
- name: CHARON_PLUGIN_DIR
  value: {{ .mountPath | quote }}
- name: CHARON_REQUIRE_SECRET_RESOLVER
  value: {{ .requireResolver | quote }}
{{- if .secrets.secretName }}
- name: CHARON_SECRETS_MOUNT_ROOT
  value: {{ .secrets.mountPath | quote }}
{{- end }}
{{- if .refs.configMapName }}
- name: CHARON_CONN_REFS_FILE
  value: {{ printf "%s/%s" .refs.mountPath .refs.fileName | quote }}
{{- end }}
{{- end }}
{{- end }}
{{- end -}}
