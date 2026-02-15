{{/*
Expand the name of the chart.
*/}}
{{- define "hazelcast-cluster.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "hazelcast-cluster.fullname" -}}
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
{{- define "hazelcast-cluster.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "hazelcast-cluster.labels" -}}
helm.sh/chart: {{ include "hazelcast-cluster.chart" . }}
{{ include "hazelcast-cluster.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: hazelcast-microservices
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "hazelcast-cluster.selectorLabels" -}}
app.kubernetes.io/name: {{ include "hazelcast-cluster.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use.
*/}}
{{- define "hazelcast-cluster.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "hazelcast-cluster.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Headless service FQDN for Kubernetes discovery.
*/}}
{{- define "hazelcast-cluster.headlessFQDN" -}}
{{- printf "%s-headless.%s.svc.cluster.local" (include "hazelcast-cluster.fullname" .) .Release.Namespace }}
{{- end }}
