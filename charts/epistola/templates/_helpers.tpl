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
Determine effective database type (handles legacy postgresql.enabled)
*/}}
{{- define "epistola.database.effectiveType" -}}
{{- if and .Values.postgresql.enabled (eq .Values.database.type "none") }}
{{- "external-legacy" }}
{{- else }}
{{- .Values.database.type }}
{{- end }}
{{- end }}
