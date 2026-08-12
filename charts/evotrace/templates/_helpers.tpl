{{/*
Expand the name of the chart.
扩展 Chart 名称。
*/}}
{{- define "evotrace.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
生成默认的完整应用名。
*/}}
{{- define "evotrace.fullname" -}}
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
生成 Chart 名称与版本标签。
*/}}
{{- define "evotrace.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
通用标签。
*/}}
{{- define "evotrace.labels" -}}
helm.sh/chart: {{ include "evotrace.chart" . }}
{{ include "evotrace.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: evotrace
{{- end }}

{{/*
Selector labels.
选择器标签（用于 Deployment / Service 匹配）。
*/}}
{{- define "evotrace.selectorLabels" -}}
app.kubernetes.io/name: {{ include "evotrace.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Service Account 名称。
*/}}
{{- define "evotrace.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "evotrace.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Service 名称（server 对外暴露的服务）。
*/}}
{{- define "evotrace.serviceName" -}}
{{- include "evotrace.fullname" . }}
{{- end }}

{{/*
镜像 tag：未显式指定时回退到 Chart appVersion。
*/}}
{{- define "evotrace.imageTag" -}}
{{- if .tag }}{{ .tag }}{{ else }}{{ .appVersion }}{{ end }}
{{- end }}

{{/*
按组件解析最终镜像：组件级配置优先，否则回退到顶层 image.repository。
参数：.component 为 "server" / "worker" / "ui"。
*/}}
{{- define "evotrace.image" -}}
{{- $top := index . 0 }}
{{- $component := index . 1 }}
{{- $comp := index $top.Values.image $component }}
{{- $repo := $comp.repository | default $top.Values.image.repository }}
{{- $tag := $comp.tag | default "" }}
{{- printf "%s:%s" $repo (include "evotrace.imageTag" (dict "tag" $tag "appVersion" $top.Chart.AppVersion)) }}
{{- end }}

{{/*
外部依赖连接环境变量（server / worker 共用）。
以 EVOTRACE_ 为前缀注入，供 Spring Boot relaxed binding 覆盖。
*/}}
{{- define "evotrace.envs" -}}
- name: EVOTRACE_EXTERNAL_POSTGRESQL_URL
  value: {{ .Values.external.postgresql.url | quote }}
- name: EVOTRACE_EXTERNAL_POSTGRESQL_USERNAME
  value: {{ .Values.external.postgresql.username | quote }}
- name: EVOTRACE_EXTERNAL_REDIS_HOST
  value: {{ .Values.external.redis.host | quote }}
- name: EVOTRACE_EXTERNAL_REDIS_PORT
  value: {{ .Values.external.redis.port | quote }}
- name: EVOTRACE_EXTERNAL_KAFKA_BOOTSTRAPSERVERS
  value: {{ .Values.external.kafka.bootstrapServers | quote }}
- name: EVOTRACE_EXTERNAL_CLICKHOUSE_URL
  value: {{ .Values.external.clickhouse.url | quote }}
- name: EVOTRACE_EXTERNAL_CLICKHOUSE_USERNAME
  value: {{ .Values.external.clickhouse.username | quote }}
- name: EVOTRACE_EXTERNAL_MINIO_ENDPOINT
  value: {{ .Values.external.minio.endpoint | quote }}
- name: EVOTRACE_EXTERNAL_MINIO_BUCKET
  value: {{ .Values.external.minio.bucket | quote }}
- name: EVOTRACE_APP_SPRINGPROFILES
  value: {{ .Values.app.springProfiles | quote }}
- name: EVOTRACE_APP_AI_BASEURL
  value: {{ .Values.app.ai.baseUrl | quote }}
- name: EVOTRACE_APP_AI_CHATMODEL
  value: {{ .Values.app.ai.chatModel | quote }}
- name: EVOTRACE_APP_EVOTRACE_BLOBDIR
  value: {{ .Values.app.evotrace.blobDir | quote }}
{{- if .Values.app.evotrace.serverUrl }}
- name: EVOTRACE_APP_EVOTRACE_SERVERURL
  value: {{ .Values.app.evotrace.serverUrl | quote }}
{{- end }}
{{- range $key, $value := .Values.app.extraEnv }}
- name: {{ $key | upper | quote }}
  value: {{ $value | quote }}
{{- end }}
{{- end }}