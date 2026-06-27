# R26 — 🟡 K8s: no PeerAuthentication policy — Istio mTLS is in permissive mode

## Problem
The `showcase` namespace has Istio injection enabled (conditionally — see R02) but no
`PeerAuthentication` CR enforces `mtls.mode: STRICT`. Without it, pods can still be reached
without mTLS from outside the mesh — plaintext connections bypass the sidecar's TLS termination.
For a showcase that claims OpenShift Service Mesh mTLS as a security feature, permissive mode
means that feature is not actually enforced.

## File to create
- `infra/k8s/infra/peer-authentication.yaml`

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: showcase
spec:
  mtls:
    mode: STRICT
```

Note: This must be applied **after** all pods have sidecars injected (R02 is a prerequisite).
Applying STRICT mode before sidecars are ready will break inter-service communication.

## Acceptance
- `istioctl authn tls-check <pod>.<namespace>` shows `mTLS` for all inter-service connections
- Services continue to communicate normally (mTLS is transparent at the application level)
- Direct plaintext access to service ports from outside the mesh is rejected
