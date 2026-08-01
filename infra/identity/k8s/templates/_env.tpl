{{/* whois container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "whois.env" -}}
- name: WHOIS_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: WHOIS_REPOSITORY_TYPE
  value: {{ .Values.repositoryType | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_WHOIS
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
