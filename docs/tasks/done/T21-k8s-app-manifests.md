# T21 · K8s Application Manifests

**Phase:** 7 — Kubernetes Manifests
**Status:** todo
**Depends on:** T20

## Deliverables
`infra/k8s/apps/` directory — one subdirectory per service:

**payment-gateway/**
- `deployment.yaml` — image `quay.io/acaglio/payment-gateway:latest`, port 8080, OTel env vars (`JAVA_TOOL_OPTIONS`, `OTEL_SERVICE_NAME=payment-gateway`, `OTEL_TRACES_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_METRICS_EXPORTER=none`), app env vars from Secrets/ConfigMaps, readiness probe `GET /actuator/health/readiness :8080`, liveness probe `GET /actuator/health/liveness :8080`
- `service.yaml` — ClusterIP, port 8080
- `route.yaml` — OpenShift Route for external access

**account-verifier/**
- `deployment.yaml` — image `quay.io/acaglio/account-verifier:latest`, OTel env vars (`OTEL_SERVICE_NAME=account-verifier`), readiness probe `GET /q/health/ready :8080`, liveness probe `GET /q/health/live :8080`
- `service.yaml` — ClusterIP, **named ports**: `grpc: 9090`, `http: 8080`

**transaction-engine/**
- `deployment.yaml` — image `quay.io/acaglio/transaction-engine:latest`, OTel env vars (`OTEL_SERVICE_NAME=transaction-engine`), readiness/liveness probes on `:8080/actuator/health/*`
- `service.yaml` — ClusterIP, port 8080

**clearing-house/**
- `deployment.yaml` — image `quay.io/acaglio/clearing-house:latest`, OTel env vars (`OTEL_SERVICE_NAME=clearing-house`), readiness probe `GET /q/health/ready :8080`
- `service.yaml` — ClusterIP, port 8080

**spa-mobile-app/**
- `deployment.yaml` — image `quay.io/acaglio/spa-mobile-app:latest`, port 80, env vars: `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`, `API_BASE_URL` (used by `docker-entrypoint.sh` envsubst)
- `service.yaml` — ClusterIP, port 80
- `route.yaml` — OpenShift Route for external access

All Deployments:
- `namespace: showcase`
- `replicas: 1`
- Resource requests/limits set (min: 256Mi/250m, max: 512Mi/500m for backends; 64Mi/100m for SPA)
- `imagePullPolicy: Always`

## Unit Tests
N/A — YAML manifests. Verification is structural.

## Verification
```bash
# If kubectl available:
kubectl apply --dry-run=client -f infra/k8s/apps/ --recursive    # zero errors

# Always:
python3 -c "
import yaml, glob, sys
files = glob.glob('infra/k8s/apps/**/*.yaml', recursive=True)
errors = []
for f in files:
    try: list(yaml.safe_load_all(open(f)))
    except Exception as e: errors.append(f'{f}: {e}')
if errors: [print(e) for e in errors]; sys.exit(1)
print(f'All {len(files)} manifests valid YAML')
"
```

## Acceptance Criteria
- [ ] All YAML files parse without error
- [ ] `kubectl apply --dry-run=client` exits 0 (if cluster available)
- [ ] All 5 services have Deployment + Service
- [ ] `payment-gateway` and `spa-mobile-app` have Routes
- [ ] `account-verifier` Service has named `grpc` and `http` ports
- [ ] OTel env vars present in all Java service Deployments
- [ ] Resource requests and limits set on all containers
