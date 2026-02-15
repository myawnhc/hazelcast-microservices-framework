{{/*
Expand the name of the chart.
*/}}
{{- define "hazelcast-microservices.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
Truncate at 63 chars because some Kubernetes name fields are limited to this.
*/}}
{{- define "hazelcast-microservices.fullname" -}}
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
{{- define "hazelcast-microservices.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels for all resources.
*/}}
{{- define "hazelcast-microservices.labels" -}}
helm.sh/chart: {{ include "hazelcast-microservices.chart" . }}
{{ include "hazelcast-microservices.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: hazelcast-microservices
{{- end }}

{{/*
Selector labels used in matchLabels and service selectors.
*/}}
{{- define "hazelcast-microservices.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hazelcast-microservices.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
