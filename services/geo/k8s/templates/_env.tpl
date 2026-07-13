{{/* geo container env — the deployment-shaped knobs; tuning defaults live in application.conf. */}}
{{- define "geo.env" -}}
- name: GEO_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: GEO_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: METADATA_HOST
  value: {{ .Values.metadata.host | quote }}
- name: METADATA_GRPC_PORT
  value: {{ .Values.metadata.grpcPort | quote }}
{{- if .Values.nominatim.baseUrl }}
- name: GEO_NOMINATIM_BASE_URL
  value: {{ .Values.nominatim.baseUrl | quote }}
{{- end }}
- name: GEO_NOMINATIM_USER_AGENT
  value: {{ .Values.nominatim.userAgent | quote }}
- name: GEO_DB_ENABLED
  value: {{ .Values.db.enabled | quote }}
{{- if .Values.db.enabled }}
- name: GEO_DB_HOST
  value: {{ .Values.db.host | quote }}
- name: GEO_DB_PORT
  value: {{ .Values.db.port | quote }}
- name: GEO_DB_NAME
  value: {{ .Values.db.name | quote }}
- name: GEO_DB_USER
  value: {{ .Values.db.user | quote }}
{{- end }}
- name: GEO_BOUNDARY_CACHE_TTL_DAYS
  value: {{ .Values.db.boundaryCacheTtlDays | quote }}
- name: GEO_USE_FIXTURE_MODEL
  value: {{ .Values.useFixtureModel | quote }}
- name: GEO_LLM_FALLBACK_ENABLED
  value: {{ .Values.llmFallback.enabled | quote }}
- name: GEO_LLM_FALLBACK_GATEWAY_URL
  value: {{ .Values.llmFallback.gatewayUrl | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_GEO
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
