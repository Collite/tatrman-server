{{/* llm-gateway container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "llm-gateway.env" -}}
- name: PROMETHEUS_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: POSTGRESQL_HOST
  value: {{ .Values.db.host | quote }}
- name: POSTGRESQL_PORT
  value: {{ .Values.db.port | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- range .Values.secretEnv }}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secretName }}
      key: {{ .secretKey }}
      {{- if .optional }}
      optional: {{ .optional }}
      {{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
