{{/* dispatch container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "dispatch.env" -}}
- name: DISPATCH_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: DISPATCH_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_DISPATCH
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
