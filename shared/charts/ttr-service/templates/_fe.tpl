{{/*
Frontend (Vue SPA on nginx) shape: a ConfigMap of runtime VITE_ + BFF_UPSTREAM_
env, a Deployment that envFrom's it (rolling on config change via a checksum
annotation), a ClusterIP Service, and an optional Gateway-API HTTPRoute for the
externally-exposed FE. Lifted from frontends/iris/k8s so iris/sysifos/landing
collapse onto the library. The checksum reads the fe-configmap template directly
(a library template cannot use $.Template.BasePath — it resolves to the library).
*/}}

{{- define "ttr-service.fe-configmap" -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "ttr-service.fullname" . }}-config
  labels:
    {{- include "ttr-service.labels" . | nindent 4 }}
data:
  # Consumed at container start by scripts/generate-env.sh (→ /env.js,
  # window.APP_CONFIG) and scripts/nginx-entrypoint.sh (→ /bff proxy).
  VITE_BFF_BASE_URL: {{ .Values.config.bffBaseUrl | quote }}
  BFF_UPSTREAM_PROTOCOL: {{ .Values.config.bffUpstream.protocol | quote }}
  BFF_UPSTREAM_HOST: {{ .Values.config.bffUpstream.host | quote }}
  BFF_UPSTREAM_PORT: {{ .Values.config.bffUpstream.port | quote }}
  VITE_AUTH_ENABLED: {{ .Values.config.auth.enabled | quote }}
  VITE_KEYCLOAK_URL: {{ .Values.config.auth.keycloakUrl | quote }}
  VITE_KEYCLOAK_REALM: {{ .Values.config.auth.realm | quote }}
  VITE_KEYCLOAK_CLIENT_ID: {{ .Values.config.auth.clientId | quote }}
  VITE_TELEMETRY_ENABLED: {{ .Values.config.telemetry.enabled | quote }}
  {{- range $k, $v := .Values.config.extra }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
{{- end -}}

{{- define "ttr-service.fe-deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "ttr-service.fullname" . }}
  labels:
    {{- include "ttr-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "ttr-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      annotations:
        # Roll the pods when the runtime config changes (env.js / proxy upstream).
        {{- /* trailing \n mirrors a rendered configmap.yaml file so the hash matches
               the pre-library $.Template.BasePath "/configmap.yaml" checksum. */}}
        checksum/config: {{ printf "%s\n" (include "ttr-service.fe-configmap" .) | sha256sum }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      labels:
        {{- include "ttr-service.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "ttr-service.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.containerPort }}
          envFrom:
            - configMapRef:
                name: {{ include "ttr-service.fullname" . }}-config
          {{- with .Values.extraEnv }}
          env:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: {{ .Values.containerPort }}
            initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.livenessProbe.path }}
              port: {{ .Values.containerPort }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}

{{- define "ttr-service.fe-service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "ttr-service.fullname" . }}
  labels:
    {{- include "ttr-service.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "ttr-service.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.containerPort }}
{{- end -}}

{{- define "ttr-service.httproute" -}}
{{- if .Values.httpRoute.enabled }}
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: {{ include "ttr-service.fullname" . }}
  labels:
    {{- include "ttr-service.labels" . | nindent 4 }}
spec:
  parentRefs:
    - group: gateway.networking.k8s.io
      kind: Gateway
      name: {{ .Values.httpRoute.gateway.name }}
      namespace: {{ .Values.httpRoute.gateway.namespace }}
  hostnames:
    - {{ required "httpRoute.hostname is required when httpRoute.enabled" .Values.httpRoute.hostname | quote }}
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /
      backendRefs:
        - group: ""
          kind: Service
          name: {{ include "ttr-service.fullname" . }}
          port: {{ .Values.service.port }}
{{- end }}
{{- end -}}
