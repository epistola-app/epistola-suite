{{/*
Expand the name of the chart.
*/}}
{{- define "epistola.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
OTEL_RESOURCE_ATTRIBUTES for an operator-attached OpenTelemetry agent.
service.instance.id uses the downward-API $(POD_NAME) so it matches the app's
own NodeIdentity (hostname → pod name). deployment.environment falls back to the
support installation environment when not set explicitly. Extra attributes from
observability.otelAgent.resourceAttributes are appended.
*/}}
{{- define "epistola.otelResourceAttributes" -}}
{{- $a := .Values.observability.otelAgent -}}
{{- $attrs := list "service.instance.id=$(POD_NAME)" -}}
{{- with $a.serviceNamespace }}{{- $attrs = append $attrs (printf "service.namespace=%s" .) -}}{{- end -}}
{{- $env := $a.deploymentEnvironment | default .Values.support.installation.environment -}}
{{- with $env }}{{- $attrs = append $attrs (printf "deployment.environment=%s" .) -}}{{- end -}}
{{- range $k, $v := $a.resourceAttributes }}{{- $attrs = append $attrs (printf "%s=%s" $k $v) -}}{{- end -}}
{{- join "," $attrs -}}
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
Get the CNPG secret name for an existing cluster's app credentials
(database.type=cnpgExisting). The chart does not create CNPG clusters; provision
the cluster (and any roles) separately — see docs/deployment.md.
*/}}
{{- define "epistola.cnpg.secretName" -}}
{{- if .Values.database.cnpgExisting.existingSecret }}
{{- .Values.database.cnpgExisting.existingSecret }}
{{- else }}
{{- printf "%s-app" .Values.database.cnpgExisting.clusterName }}
{{- end }}
{{- end }}

{{/*
Resolve and validate database.type. Every datasource helper funnels through
here, so an unsupported type fails the render once, with a clear message,
regardless of which template is rendered first.
*/}}
{{- define "epistola.database.effectiveType" -}}
{{- $t := .Values.database.type -}}
{{- if not (has $t (list "none" "external" "cnpgExisting")) -}}
{{- fail (printf "database.type=%q is not supported. Use 'external', 'cnpgExisting', or 'none'. The chart no longer provisions a CloudNativePG cluster (a database must not share the app release's lifecycle); manage the CNPG Cluster yourself (see charts/epistola/examples/cnpg-cluster.yaml) and use database.type=cnpgExisting." $t) -}}
{{- end -}}
{{- $t -}}
{{- end }}

{{/*
Credentials-free JDBC URL for database.type=external, shared by the app and the
migration container so the URL (and its required-host guard) live in one place.
*/}}
{{- define "epistola.database.externalUrl" -}}
jdbc:postgresql://{{ required "database.external.host is required when database.type is 'external'" .Values.database.external.host }}:{{ .Values.database.external.port }}/{{ .Values.database.external.database }}
{{- end }}

{{/*
Datasource env (SPRING_DATASOURCE_*). Shared by the app Deployment, the
migration Job and the migration init container so credentials are wired
identically everywhere. For database.type=none the caller relies on
config.env to provide the datasource.
*/}}
{{- define "epistola.databaseEnv" -}}
{{- $dbType := include "epistola.database.effectiveType" . -}}
{{- if eq $dbType "cnpgExisting" }}
{{- /* cnpgExisting is single-role: the app connects as the CNPG cluster owner
       via the `-app` secret. A two-role setup (restricted app role) on CNPG is
       done with database.type=external pointed at the cluster's -rw service —
       NOT here — because CNPG's jdbc-uri embeds the owner credentials, which
       pgjdbc lets override an explicit username. See docs/deployment.md. */}}
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
  value: {{ include "epistola.database.externalUrl" . | quote }}
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
Datasource env for the MIGRATION container (SPRING_DATASOURCE_*).

The migration step needs the DDL-holding role. Resolution order:
  1. `migration.credentials.username` set (external only) → connect as that
     explicit role — the two-role setup where the app runs as a restricted role
     and migrations run as the DDL-holding role. Fails the render with
     `cnpgExisting` (single-role: the app is already the owner); use `external`
     for a two-role CNPG setup.
  2. Otherwise → reuse the app credentials from `epistola.databaseEnv`
     (single-role: `external`/`none` as configured, `cnpgExisting` as the owner).

The operator provisions any extra roles and their Secrets out of band; this only
wires the migration container to the right one.
*/}}
{{- define "epistola.migrationDatabaseEnv" -}}
{{- $mig := .Values.migration.credentials | default dict -}}
{{- $dbType := include "epistola.database.effectiveType" . -}}
{{- if $mig.username }}
{{- if eq $dbType "cnpgExisting" }}
{{- fail "migration.credentials is not supported with database.type=cnpgExisting: the app already connects as the CNPG cluster owner, which holds DDL, so no separate migration role is needed. For a two-role setup on CNPG, use database.type=external pointed at the cluster's -rw service (see docs/deployment.md)." }}
{{- else if eq $dbType "external" }}
- name: SPRING_DATASOURCE_URL
  value: {{ include "epistola.database.externalUrl" . | quote }}
{{- end }}
- name: SPRING_DATASOURCE_USERNAME
  value: {{ $mig.username | quote }}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "migration.credentials.existingSecret is required when migration.credentials.username is set" $mig.existingSecret }}
      key: {{ $mig.secretKey | default "password" }}
{{- else }}
{{- /* Single-role: migration reuses the app credentials. For cnpgExisting that
       is the cluster owner (-app secret); a two-role CNPG setup uses
       database.type=external (see the fail branch above and docs/deployment.md). */}}
{{- include "epistola.databaseEnv" . }}
{{- end }}
{{- end }}

{{/*
Credential encryption-at-rest env (EPISTOLA_ENCRYPTION_*). Only the app
Deployment needs this — the migration Job/init container never touch ciphertext.
Each key's material is sourced from a Kubernetes Secret (existingSecret/secretKey);
inline key material is intentionally not supported — the chart never places secret
material in the pod spec or the Helm release.
*/}}
{{- define "epistola.encryptionEnv" -}}
{{- if .Values.encryption.enabled }}
- name: EPISTOLA_ENCRYPTION_ENABLED
  value: "true"
- name: EPISTOLA_ENCRYPTION_PRIMARYKEYID
  value: {{ required "encryption.primaryKeyId is required when encryption.enabled is true" .Values.encryption.primaryKeyId | quote }}
{{- range $i, $key := .Values.encryption.keys }}
- name: EPISTOLA_ENCRYPTION_KEYS_{{ $i }}_ID
  value: {{ required "each encryption.keys entry requires an id" $key.id | quote }}
- name: EPISTOLA_ENCRYPTION_KEYS_{{ $i }}_MATERIAL
  valueFrom:
    secretKeyRef:
      name: {{ required "each encryption.keys entry requires existingSecret (inline key material is not supported)" $key.existingSecret }}
      key: {{ $key.secretKey | default $key.id }}
{{- end }}
{{- else }}
- name: EPISTOLA_ENCRYPTION_ENABLED
  value: "false"
{{- end }}
{{- end }}

{{/*
PGHOST/PGPORT env for the wait-for-db probe container. Empty for
database.type=none (caller must skip the wait container).
*/}}
{{- define "epistola.databaseProbeEnv" -}}
{{- $dbType := include "epistola.database.effectiveType" . -}}
{{- if eq $dbType "cnpgExisting" }}
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
Migration container — same app image, EPISTOLA_MIGRATION_MODE=migrate. Used by the
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
    {{- include "epistola.migrationDatabaseEnv" . | nindent 4 }}
    {{- /* Migration JVM diverges from the app's Hikari defaults (which it would
           otherwise inherit from application.yaml): long DDL can read with no
           socket traffic past the app's socketTimeout, and a long migration would
           trip the app's 60s leak detector. Both are relaxed here only. */}}
    - name: SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SOCKETTIMEOUT
      value: {{ .Values.migration.socketTimeoutSeconds | default 0 | quote }}
    - name: SPRING_DATASOURCE_HIKARI_LEAKDETECTIONTHRESHOLD
      value: "0"
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
