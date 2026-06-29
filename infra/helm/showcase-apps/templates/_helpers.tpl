{{/*
OTel environment variables for Java services.
Usage: {{ include "showcase.otelEnv" (dict "root" . "serviceName" "my-service") }}
*/}}
{{- define "showcase.otelEnv" -}}
- name: JAVA_TOOL_OPTIONS
  value: "-javaagent:/opt/otel/opentelemetry-javaagent.jar"
- name: OTEL_TRACES_EXPORTER
  value: "otlp"
- name: OTEL_EXPORTER_OTLP_PROTOCOL
  value: "grpc"
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .root.Values.otel.collectorEndpoint | quote }}
- name: OTEL_METRICS_EXPORTER
  value: "none"
- name: OTEL_LOGS_EXPORTER
  value: "none"
- name: OTEL_PROPAGATORS
  value: "tracecontext,baggage"
- name: OTEL_SERVICE_NAME
  value: {{ .serviceName | quote }}
{{- end }}

{{/*
Keycloak external URL (for browser-facing redirects and SPA config).
*/}}
{{- define "showcase.keycloakExternalUrl" -}}
{{- if .Values.keycloak.externalUrl -}}
{{ .Values.keycloak.externalUrl }}
{{- else -}}
{{ printf "https://keycloak.%s" .Values.clusterDomain }}
{{- end -}}
{{- end }}

{{/*
Keycloak issuer URI used by payment-gateway for JWT validation.
*/}}
{{- define "showcase.keycloakIssuerUri" -}}
{{- if .Values.keycloak.externalUrl -}}
{{ printf "%s/realms/%s" .Values.keycloak.externalUrl .Values.keycloak.realm }}
{{- else -}}
{{ printf "https://keycloak.%s/realms/%s" .Values.clusterDomain .Values.keycloak.realm }}
{{- end -}}
{{- end }}

{{/*
Keycloak JWK set URI for payment-gateway. Uses internal cluster-local URL by default
to avoid the external round-trip and TLS overhead on every token validation.
*/}}
{{- define "showcase.keycloakJwkSetUri" -}}
{{- if .Values.keycloak.jwkSetUri -}}
{{ .Values.keycloak.jwkSetUri }}
{{- else -}}
{{ printf "http://keycloak.%s.svc.cluster.local:8080/realms/%s/protocol/openid-connect/certs" .Values.namespace .Values.keycloak.realm }}
{{- end -}}
{{- end }}

{{/*
CORS allowed origins for payment-gateway.
*/}}
{{- define "showcase.corsOrigins" -}}
{{- if .Values.cors.allowedOrigins -}}
{{ .Values.cors.allowedOrigins }}
{{- else -}}
{{ printf "https://spa-mobile-app.%s" .Values.clusterDomain }}
{{- end -}}
{{- end }}

{{/*
IBM MQ connection name in the format expected by the JMS client: HOST(PORT)
*/}}
{{- define "showcase.mqConnName" -}}
{{ printf "%s(%v)" .Values.ibmmq.host .Values.ibmmq.port }}
{{- end }}
