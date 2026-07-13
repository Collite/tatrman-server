{{/* ttr-grounding-mcp container env — the MCP HTTP port + the three backend gRPC targets. */}}
{{- define "ttr-grounding-mcp.env" -}}
- name: MCP_GROUNDING_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: CHRONO_HOST
  value: {{ .Values.backends.chrono.host | quote }}
- name: CHRONO_GRPC_PORT
  value: {{ .Values.backends.chrono.grpcPort | quote }}
- name: GEO_HOST
  value: {{ .Values.backends.geo.host | quote }}
- name: GEO_GRPC_PORT
  value: {{ .Values.backends.geo.grpcPort | quote }}
- name: MONEY_HOST
  value: {{ .Values.backends.money.host | quote }}
- name: MONEY_GRPC_PORT
  value: {{ .Values.backends.money.grpcPort | quote }}
- name: OTEL_ENABLED_GROUNDING_MCP
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
