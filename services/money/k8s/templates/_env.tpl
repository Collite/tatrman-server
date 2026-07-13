{{/* money container env — the deployment-shaped knobs; tuning defaults live in application.conf. */}}
{{- define "money.env" -}}
- name: MONEY_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: MONEY_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: METADATA_HOST
  value: {{ .Values.metadata.host | quote }}
- name: METADATA_GRPC_PORT
  value: {{ .Values.metadata.grpcPort | quote }}
- name: MONEY_DEFAULT_CURRENCY
  value: {{ .Values.defaultCurrency | quote }}
- name: MONEY_USE_FIXTURE_MODEL
  value: {{ .Values.useFixtureModel | quote }}
- name: MONEY_LLM_FALLBACK_ENABLED
  value: {{ .Values.llmFallback.enabled | quote }}
- name: MONEY_LLM_FALLBACK_GATEWAY_URL
  value: {{ .Values.llmFallback.gatewayUrl | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_MONEY
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
