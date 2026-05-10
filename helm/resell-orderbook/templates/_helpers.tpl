{{/*
공통 helper. Helm 표준 패턴 (name / fullname / labels / selectorLabels / serviceAccountName).
*/}}

{{- define "resell-orderbook.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "resell-orderbook.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "resell-orderbook.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "resell-orderbook.labels" -}}
helm.sh/chart: {{ include "resell-orderbook.chart" . }}
{{ include "resell-orderbook.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: resell-orderbook
{{- end -}}

{{- define "resell-orderbook.selectorLabels" -}}
app.kubernetes.io/name: {{ include "resell-orderbook.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "resell-orderbook.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "resell-orderbook.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
이미지 태그가 비면 Chart.AppVersion 으로 fallback.
*/}}
{{- define "resell-orderbook.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
wiremock 관련 helper.
*/}}
{{- define "resell-orderbook.wiremock.fullname" -}}
{{- printf "%s-wiremock" (include "resell-orderbook.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "resell-orderbook.wiremock.selectorLabels" -}}
app.kubernetes.io/name: {{ include "resell-orderbook.name" . }}-wiremock
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: pg-mock
{{- end -}}

{{- define "resell-orderbook.wiremock.labels" -}}
helm.sh/chart: {{ include "resell-orderbook.chart" . }}
{{ include "resell-orderbook.wiremock.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: resell-orderbook
{{- end -}}
