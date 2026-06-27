# R02 — 🔴 K8s: No ServiceMeshMemberRoll — Istio sidecar injection inactive

## Problem
`infra/k8s/infra/namespace.yaml` labels the `showcase` namespace with `istio-injection: enabled`,
but OpenShift Service Mesh 3 requires the namespace to be listed in a `ServiceMeshMemberRoll`
resource in the control plane namespace. Without this CR, the label is ignored, no sidecars are
injected, and the Istio layer of the demo does not function.

## Files to create
- `infra/k8s/infra/servicemesh-member-roll.yaml`

```yaml
apiVersion: maistra.io/v1
kind: ServiceMeshMemberRoll
metadata:
  name: default
  namespace: istio-system          # control plane namespace — adjust if different
spec:
  members:
    - showcase
```

## Acceptance
- `oc get pods -n showcase` shows all app pods with a `2/2` container count (app + istio-proxy)
- `istioctl proxy-status` lists all showcase pods
