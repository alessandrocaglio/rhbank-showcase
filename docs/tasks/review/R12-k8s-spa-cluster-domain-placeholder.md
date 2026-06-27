# R12 — 🔴 K8s: spa-mobile-app deployment has literal `<cluster-domain>` placeholder

## Problem
`infra/k8s/apps/spa-mobile-app/deployment.yaml` contains:
```yaml
value: "https://keycloak-showcase.apps.<cluster-domain>"
value: "https://payment-gateway-showcase.apps.<cluster-domain>"
```
These literal angle-bracket placeholders make the manifest un-applyable as-is and will cause the
SPA to misconfigure itself at runtime (Keycloak won't recognise the URL, API calls will fail).

## Files to change
- `infra/k8s/apps/spa-mobile-app/deployment.yaml`

## Fix options
**Option A (minimal):** Replace placeholders with a documented comment and provide a `kustomize`
patch or a simple `sed` substitution in the README deploy instructions.

**Option B (recommended):** Introduce a `kustomize` overlay structure:
```
infra/k8s/
├── base/
│   └── apps/spa-mobile-app/deployment.yaml   (placeholder values)
└── overlays/
    └── demo/
        └── apps/spa-mobile-app/patch.yaml    (actual cluster domain)
```

At minimum, document in `infra/k8s/apps/spa-mobile-app/README.md` that `<cluster-domain>` must
be substituted before applying.

## Acceptance
- `oc apply -f infra/k8s/apps/` (or `kubectl apply` with kustomize) works without manual edits
- SPA pod correctly receives `KEYCLOAK_URL` and `API_BASE_URL` at runtime
