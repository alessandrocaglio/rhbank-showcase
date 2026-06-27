# R20 — 🟠 Keycloak: `directAccessGrantsEnabled` true in Docker realm, false in K8s

## Problem
`infra/docker/payment-realm.json:32`: `"directAccessGrantsEnabled": true`
`infra/k8s/infra/keycloak/configmap-realm.yaml:42`: `"directAccessGrantsEnabled": false`

The password-grant flow (ROPC) is enabled in Docker Compose (used by the smoke test script
`./showcase.sh smoke`) but disabled in K8s. The smoke test will fail on OpenShift. Additionally,
the ROPC grant is deprecated in OAuth 2.1 and should not be demonstrated in a security-conscious
showcase even in local dev.

## Files to change
- `infra/docker/payment-realm.json`
- `showcase.sh` (update smoke test to use proper PKCE flow or a test-only service account)

## Fix
Set `directAccessGrantsEnabled: false` in the Docker realm to match K8s.
Update `showcase.sh smoke` to obtain a token via the device authorization flow or create a
dedicated `test-service-client` with `serviceAccountsEnabled: true` and client credentials grant
(which is appropriate for machine-to-machine testing).

## Acceptance
- Both Docker and K8s realms have `directAccessGrantsEnabled: false`
- `./showcase.sh smoke` still obtains a valid JWT and completes the pipeline test
