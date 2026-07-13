{{/* chrono container env — the deployment-shaped knobs; tuning defaults live in application.conf. */}}
{{- define "chrono.env" -}}
- name: CHRONO_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: CHRONO_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: METADATA_HOST
  value: {{ .Values.metadata.host | quote }}
- name: METADATA_GRPC_PORT
  value: {{ .Values.metadata.grpcPort | quote }}
- name: CHRONO_USE_FIXTURE_MODEL
  value: {{ .Values.useFixtureModel | quote }}
- name: CHRONO_LLM_FALLBACK_ENABLED
  value: {{ .Values.llmFallback.enabled | quote }}
- name: CHRONO_LLM_FALLBACK_GATEWAY_URL
  value: {{ .Values.llmFallback.gatewayUrl | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_CHRONO
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
