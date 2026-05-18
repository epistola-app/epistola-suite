{{/*
Expand the name of the chart.
*/}}
{{- define "epistola.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "epistola.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "epistola.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "epistola.labels" -}}
helm.sh/chart: {{ include "epistola.chart" . }}
{{ include "epistola.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "epistola.selectorLabels" -}}
app.kubernetes.io/name: {{ include "epistola.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "epistola.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "epistola.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Get the CNPG cluster name
*/}}
{{- define "epistola.cnpg.clusterName" -}}
{{- if .Values.database.cnpg.name }}
{{- .Values.database.cnpg.name }}
{{- else }}
{{- printf "%s-db" (include "epistola.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Get the CNPG secret name for app credentials
*/}}
{{- define "epistola.cnpg.secretName" -}}
{{- if eq .Values.database.type "cnpgExisting" }}
{{- if .Values.database.cnpgExisting.secretName }}
{{- .Values.database.cnpgExisting.secretName }}
{{- else }}
{{- printf "%s-app" .Values.database.cnpgExisting.clusterName }}
{{- end }}
{{- else }}
{{- printf "%s-app" (include "epistola.cnpg.clusterName" .) }}
{{- end }}
{{- end }}

{{/*
Determine effective database type
*/}}
{{- define "epistola.database.effectiveType" -}}
{{- .Values.database.type }}
{{- end }}

{{/*
Datasource env (SPRING_DATASOURCE_*). Shared by the app Deployment, the
migration Job and the migration init container so credentials are wired
identically everywhere. For database.type=none the caller relies on
config.env to provide the datasource.
*/}}
{{- define "epistola.databaseEnv" -}}
{{- $dbType := include "epistola.database.effectiveType" . -}}
{{- if or (eq $dbType "cnpg") (eq $dbType "cnpgExisting") }}
- name: SPRING_DATASOURCE_URL
  valueFrom:
    secretKeyRef:
      name: {{ include "epistola.cnpg.secretName" . }}
      key: jdbc-uri
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "epistola.cnpg.secretName" . }}
      key: username
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "epistola.cnpg.secretName" . }}
      key: password
{{- else if eq $dbType "external" }}
- name: SPRING_DATASOURCE_URL
  value: "jdbc:postgresql://{{ .Values.database.external.host }}:{{ .Values.database.external.port }}/{{ .Values.database.external.database }}"
- name: SPRING_DATASOURCE_USERNAME
  value: {{ .Values.database.external.username | quote }}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "database.external.existingSecret is required when database.type is 'external'" .Values.database.external.existingSecret }}
      key: {{ .Values.database.external.secretKey }}
{{- end }}
{{- end }}

{{/*
PGHOST/PGPORT env for the wait-for-db probe container. Empty for
database.type=none (caller must skip the wait container).
*/}}
{{- define "epistola.databaseProbeEnv" -}}
{{- $dbType := include "epistola.database.effectiveType" . -}}
{{- if or (eq $dbType "cnpg") (eq $dbType "cnpgExisting") }}
- name: PGHOST
  valueFrom:
    secretKeyRef:
      name: {{ include "epistola.cnpg.secretName" . }}
      key: host
- name: PGPORT
  valueFrom:
    secretKeyRef:
      name: {{ include "epistola.cnpg.secretName" . }}
      key: port
{{- else if eq $dbType "external" }}
- name: PGHOST
  value: {{ .Values.database.external.host | quote }}
- name: PGPORT
  value: {{ .Values.database.external.port | quote }}
{{- end }}
{{- end }}

{{/*
wait-for-db init container — blocks until the database TCP port is reachable.
Handles CNPG's asynchronous provisioning. Rendered as a container list item.
*/}}
{{- define "epistola.waitForDb" -}}
- name: wait-for-db
  image: {{ .Values.migration.waitImage }}
  imagePullPolicy: IfNotPresent
  securityContext:
    {{- toYaml .Values.securityContext | nindent 4 }}
  command:
    - sh
    - -c
    - |
      until nc -z "$PGHOST" "$PGPORT"; do
        echo "waiting for database $PGHOST:$PGPORT"
        sleep 3
      done
  env:
    {{- include "epistola.databaseProbeEnv" . | nindent 4 }}
{{- end }}

{{/*
Migration container — same app image, EPISTOLA_RUN_MODE=migrate. Used by the
hook Job (job mode) and the app pod init container (initContainer mode).
Rendered as a container list item.
*/}}
{{- define "epistola.migrateContainer" -}}
- name: migrate
  securityContext:
    {{- toYaml .Values.securityContext | nindent 4 }}
  image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
  imagePullPolicy: {{ .Values.image.pullPolicy }}
  env:
    - name: EPISTOLA_MIGRATION_MODE
      value: "migrate"
    {{- if .Values.jvm.options }}
    - name: JAVA_TOOL_OPTIONS
      value: {{ .Values.jvm.options | quote }}
    {{- end }}
    {{- include "epistola.databaseEnv" . | nindent 4 }}
    {{- range $key, $value := .Values.config.env }}
    - name: {{ $key }}
      value: {{ $value | quote }}
    {{- end }}
  resources:
    {{- toYaml .Values.migration.resources | nindent 4 }}
  {{- with .Values.volumeMounts }}
  volumeMounts:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
