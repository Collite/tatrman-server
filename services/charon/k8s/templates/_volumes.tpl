{{/*
Charon overrides the kantheon-service volume hooks (the consuming chart's define wins
over the library's empty default) to mount the named-connection registry ConfigMap
read-only at connections.mountPath, gated on connections.configMapName. The ConfigMap
is created by the deploying context (olymp / `kubectl create configmap`), not templated
here — its content carries ${ENV} credential tokens and is environment-specific.
*/}}
{{- define "kantheon-service.volumeMounts" -}}
{{- if .Values.connections.configMapName }}
          volumeMounts:
            - name: connections
              mountPath: {{ .Values.connections.mountPath }}
              readOnly: true
{{- end }}
{{- end -}}
{{- define "kantheon-service.volumes" -}}
{{- if .Values.connections.configMapName }}
      volumes:
        - name: connections
          configMap:
            name: {{ .Values.connections.configMapName }}
{{- end }}
{{- end -}}
