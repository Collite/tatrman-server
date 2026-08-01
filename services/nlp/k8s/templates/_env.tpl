{{/* nlp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "nlp.env" -}}
- name: UVICORN_PORT
  value: {{ .Values.ports.http | quote }}
- name: NLP_SERVICE_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: NLP_SERVICE_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
{{- if and .Values.telemetry.enabled .Values.telemetry.otlpHost }}
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | quote }}
- name: NLP_SERVICE_OTEL_PROTOCOL
  value: {{ .Values.telemetry.protocol | quote }}
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
