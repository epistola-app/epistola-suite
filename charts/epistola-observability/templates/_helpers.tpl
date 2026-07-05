{{/*
Expand the name of the chart.
*/}}
{{- define "epistola-observability.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name (truncated to 63 chars for DNS names).
*/}}
{{- define "epistola-observability.fullname" -}}
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
Chart name and version as used by the chart label.
*/}}
{{- define "epistola-observability.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "epistola-observability.labels" -}}
helm.sh/chart: {{ include "epistola-observability.chart" . }}
{{ include "epistola-observability.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "epistola-observability.selectorLabels" -}}
app.kubernetes.io/name: {{ include "epistola-observability.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The Grafana folder UID that dashboards and alerts anchor to.

The folder UID is the immutable identity in both Grafana and the CRD
(GrafanaAlertRuleGroup.spec.folderUID is immutable — CEL `self == oldSelf`). We
pin it — default it to the release fullname — so it is stable by construction:
the immutable field only ever holds a value that never needs to change, and the
display title (grafana.folder.title) can be renamed freely without touching it.
*/}}
{{- define "epistola-observability.folderUid" -}}
{{- .Values.grafana.folder.uid | default (include "epistola-observability.fullname" .) -}}
{{- end }}
