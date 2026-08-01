{{/*
Charon overrides the tatrman-service volume hooks (the consuming chart's define wins over
the library's empty default). Two independent, off-by-default features share these
hooks, so the `volumeMounts:`/`volumes:` keys are emitted once when EITHER is on:

  1. connections — the named-connection registry ConfigMap mounted read-only at
     connections.mountPath, gated on connections.configMapName. The ConfigMap is created
     by the deploying context (olymp), not templated here — its content carries ${ENV}
     credential tokens and is environment-specific.

  2. pluginDir (CH-D8 / CH-P3 T2b) — the platform secret-resolver adapter, mounted at
     runtime so the open image stays free of cz.tatrman bytes. An initContainer copies
     the adapter's jar(s) out of a platform-published image (pluginDir.image) into a
     shared emptyDir, which the main container reads as CHARON_PLUGIN_DIR. Optionally a
     secret volume (the FileSecretStore material) and a refs ConfigMap ride alongside.
     Gated on pluginDir.image; empty ⇒ no initContainer, no mount, env-default resolver.
*/}}
{{- define "tatrman-service.initContainers" -}}
{{- with .Values.pluginDir }}
{{- if .image }}
      initContainers:
        - name: secret-resolver-plugin
          image: {{ .image | quote }}
          imagePullPolicy: {{ .pullPolicy | default "IfNotPresent" }}
          # Jib (packaged mode) lays the module + its deps down as separate *.jar files
          # under /app; the open loader globs *.jar in the plugin dir, so copy them all
          # (parent-first delegation dedupes the charon-provided ones). GNU cp -t = the
          # eclipse-temurin base is Ubuntu, so no `\;` escaping in YAML.
          command: ["sh", "-c", "find /app -name '*.jar' -exec cp -t {{ .mountPath }} {} +"]
          volumeMounts:
            - name: charon-plugins
              mountPath: {{ .mountPath }}
{{- end }}
{{- end }}
{{- end -}}
{{- define "tatrman-service.volumeMounts" -}}
{{- $conn := and .Values.connections .Values.connections.configMapName }}
{{- $plugin := and .Values.pluginDir .Values.pluginDir.image }}
{{- if or $conn $plugin }}
          volumeMounts:
{{- if $conn }}
            - name: connections
              mountPath: {{ .Values.connections.mountPath }}
              readOnly: true
{{- end }}
{{- if $plugin }}
{{- with .Values.pluginDir }}
            - name: charon-plugins
              mountPath: {{ .mountPath }}
              readOnly: true
{{- if .secrets.secretName }}
            - name: charon-secret-mount
              mountPath: {{ .secrets.mountPath }}
              readOnly: true
{{- end }}
{{- if .refs.configMapName }}
            - name: charon-conn-refs
              mountPath: {{ .refs.mountPath }}
              readOnly: true
{{- end }}
{{- end }}
{{- end }}
{{- end }}
{{- end -}}
{{- define "tatrman-service.volumes" -}}
{{- $conn := and .Values.connections .Values.connections.configMapName }}
{{- $plugin := and .Values.pluginDir .Values.pluginDir.image }}
{{- if or $conn $plugin }}
      volumes:
{{- if $conn }}
        - name: connections
          configMap:
            name: {{ .Values.connections.configMapName }}
{{- end }}
{{- if $plugin }}
{{- with .Values.pluginDir }}
        - name: charon-plugins
          emptyDir: {}
{{- if .secrets.secretName }}
        - name: charon-secret-mount
          secret:
            secretName: {{ .secrets.secretName }}
{{- end }}
{{- if .refs.configMapName }}
        - name: charon-conn-refs
          configMap:
            name: {{ .refs.configMapName }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}
{{- end -}}
