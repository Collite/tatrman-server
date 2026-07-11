{{/* echo container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "echo.env" -}}
- name: ECHO_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: ECHO_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_ECHO
  value: {{ .Values.telemetry.enabled | quote }}
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
