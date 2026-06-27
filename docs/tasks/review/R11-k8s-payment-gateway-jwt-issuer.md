# R11 — 🔴 K8s: payment-gateway rejects all tokens due to JWT issuer mismatch

## Problem
`infra/k8s/apps/payment-gateway/deployment.yaml:40`:
```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://keycloak:8080/realms/BankDemoRealm
```
Tokens issued via the OpenShift Route carry `iss: https://keycloak-showcase.apps.<cluster>/realms/BankDemoRealm`.
Spring Security validates `iss` against the configured issuer URI → mismatch → every token rejected
with 401. All payment requests fail.

The Docker Compose fix (setting both `ISSUER_URI` pointing to the browser-visible URL and
`JWK_SET_URI` pointing to the internal cluster service) is absent from the K8s deployment.

## File to change
- `infra/k8s/apps/payment-gateway/deployment.yaml`

## Fix
```yaml
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
  value: "https://keycloak-showcase.apps.<cluster-domain>/realms/BankDemoRealm"
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI
  value: "http://keycloak:8080/realms/BankDemoRealm/protocol/openid-connect/certs"
```

`ISSUER_URI` validates the `iss` claim (matches what the browser-accessible Keycloak Route issues).
`JWK_SET_URI` fetches signing keys from the internal cluster service (no DNS/TLS complexity).

These values should be parameterised via Kustomize or a ConfigMap rather than hardcoded.

## Acceptance
- `POST /api/v1/payments` with a valid Keycloak token returns 202 on OpenShift
