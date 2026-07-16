{{/*
ttr-resolver container env — the HTTP/gRPC/MCP ports, the nlp + fuzzy gRPC targets, and the
HMAC resume-token wiring. Env-agnostic: deploying environments override the hosts/ports,
telemetry, and the resume-token key material (secretEnv). The `resolve.bind:v1` MCP door
binds loopback by default (RG-P6 review D) — a deploying env sets RESOLVER_MCP_HOST=0.0.0.0
only behind an auth-terminating ingress.
*/}}
{{- define "ttr-resolver.env" -}}
- name: RESOLVER_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
{{- if .Values.ports.grpc }}
- name: RESOLVER_SERVER_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
{{- end }}
- name: RESOLVER_MCP_PORT
  value: {{ .Values.mcp.port | quote }}
- name: RESOLVER_MCP_HOST
  value: {{ .Values.mcp.host | quote }}
- name: RESOLVER_MCP_REQUIRE_IDENTITY
  value: {{ .Values.mcp.requireIdentity | quote }}
- name: RESOLVER_MCP_TRUST_NETWORK
  value: {{ .Values.mcp.trustNetwork | quote }}
- name: NLP_HOST
  value: {{ .Values.nlp.host | quote }}
- name: NLP_GRPC_PORT
  value: {{ .Values.nlp.grpcPort | quote }}
{{- if .Values.nlp.deadlineSeconds }}
- name: NLP_GRPC_DEADLINE_SECONDS
  value: {{ .Values.nlp.deadlineSeconds | quote }}
{{- end }}
- name: FUZZY_HOST
  value: {{ .Values.fuzzy.host | quote }}
- name: FUZZY_GRPC_PORT
  value: {{ .Values.fuzzy.grpcPort | quote }}
{{- if .Values.fuzzy.deadlineSeconds }}
- name: FUZZY_GRPC_DEADLINE_SECONDS
  value: {{ .Values.fuzzy.deadlineSeconds | quote }}
{{- end }}
{{- with .Values.resumeToken.activeKeyId }}
- name: RESOLVER_RESUME_ACTIVE_KEY_ID
  value: {{ . | quote }}
{{- end }}
- name: RESOLVER_RESUME_MAX_AGE_SECONDS
  value: {{ .Values.resumeToken.maxAgeSeconds | quote }}
- name: RESOLVER_RESUME_ALLOW_EPHEMERAL_KEY
  value: {{ .Values.resumeToken.allowEphemeralKey | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_RESOLVER
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
