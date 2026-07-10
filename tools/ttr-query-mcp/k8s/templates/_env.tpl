{{/* theseus-mcp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "theseus-mcp.env" -}}
- name: THESEUS_MCP_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: THESEUS_MCP_REQUIRE_IDENTITY
  value: {{ .Values.requireIdentity | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_THESEUS_MCP
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
