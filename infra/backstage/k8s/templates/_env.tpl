{{/* backstage container env — non-secret wiring via extraEnv + secretKeyRefs for the
     Postgres password, the backend session secret, and the Keycloak OIDC client
     secret. Backstage reads its listen port from app-config.yaml (:7007), not env,
     and does not consume the OTEL_ENABLED_* idiom, so no port/OTel env is emitted. */}}
{{- define "backstage.env" -}}
{{- with .Values.secrets.db }}
- name: BACKSTAGE_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .secretName | quote }}
      key: {{ .passwordKey | quote }}
      optional: true
{{- end }}
{{- with .Values.secrets.session }}
- name: BACKSTAGE_SESSION_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .secretName | quote }}
      key: {{ .key | quote }}
      optional: true
{{- end }}
{{- with .Values.secrets.keycloak }}
- name: KEYCLOAK_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .secretName | quote }}
      key: {{ .key | quote }}
      optional: true
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
