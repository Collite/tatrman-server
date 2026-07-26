{{/*
Charon overrides the ttr-service volume hooks (the consuming chart's define wins
over the library's empty default) to mount the named-connection registry ConfigMap
read-only at connections.mountPath, gated on connections.configMapName. The ConfigMap
is created by the deploying context (olymp / `kubectl create configmap`), not templated
here — its content carries ${ENV} credential tokens and is environment-specific.
*/}}
{{- define "ttr-service.volumeMounts" -}}
{{- with .Values.connections }}
{{- if .configMapName }}
          volumeMounts:
            - name: connections
              mountPath: {{ .mountPath }}
              readOnly: true
{{- end }}
{{- end }}
{{- end -}}
{{- define "ttr-service.volumes" -}}
{{- with .Values.connections }}
{{- if .configMapName }}
      volumes:
        - name: connections
          configMap:
            name: {{ .configMapName }}
{{- end }}
{{- end }}
{{- end -}}
