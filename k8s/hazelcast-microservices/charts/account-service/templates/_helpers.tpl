{{- define "account-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "account-service.fullname" -}}
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

{{- define "account-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "account-service.labels" -}}
helm.sh/chart: {{ include "account-service.chart" . }}
{{ include "account-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: hazelcast-microservices
{{- end }}

{{- define "account-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "account-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use.
*/}}
{{- define "account-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "account-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Fully qualified DNS name for the embedded Hazelcast headless service.
*/}}
{{- define "account-service.embeddedHzHeadlessFQDN" -}}
{{ include "account-service.fullname" . }}-hz-embedded.{{ .Release.Namespace }}.svc.cluster.local
{{- end }}
