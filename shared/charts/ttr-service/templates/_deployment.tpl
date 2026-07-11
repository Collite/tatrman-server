{{/*
Backend (Kotlin/Ktor service · agent · worker · Python service) Deployment.
The per-module container env block is delegated to `<module>.env` (defined in the
consuming chart). The gRPC port + its env are gated on `.Values.ports.grpc` so
HTTP-only modules omit them. Optional volumeMounts/volumes come from the override
hooks (ttr-service.volumeMounts / .volumes), empty unless a module needs them.
*/}}
{{- define "ttr-service.deployment" -}}
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
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      labels:
        {{- include "ttr-service.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- include "ttr-service.initContainers" . }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "ttr-service.image" . | quote }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.ports.http }}
            {{- if .Values.ports.grpc }}
            - name: grpc
              containerPort: {{ .Values.ports.grpc }}
            {{- end }}
          env:
            {{- include (printf "%s.env" .Chart.Name) . | nindent 12 }}
          {{- with .Values.envFrom }}
          envFrom:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if .Values.startupProbe }}
          startupProbe:
            httpGet:
              path: {{ .Values.startupProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.startupProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.startupProbe.periodSeconds }}
            failureThreshold: {{ .Values.startupProbe.failureThreshold }}
          {{- end }}
          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.readinessProbe.periodSeconds }}
            failureThreshold: {{ .Values.readinessProbe.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ .Values.livenessProbe.path }}
              port: {{ .Values.ports.http }}
            initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
            periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- include "ttr-service.volumeMounts" . }}
      {{- include "ttr-service.volumes" . }}
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
