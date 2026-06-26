# T20 · K8s Infrastructure Manifests

**Phase:** 7 — Kubernetes Manifests
**Status:** todo
**Depends on:** T03

## Deliverables
`infra/k8s/infra/` directory containing:
- `namespace.yaml` — namespace `showcase`
- `keycloak/configmap-realm.yaml` — `payment-realm.json` as ConfigMap data key
- `keycloak/deployment.yaml` — image `quay.io/keycloak/keycloak:25.0.6`, mounts realm ConfigMap at `/opt/keycloak/data/import/`, command `start-dev --import-realm`, env: `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KC_HTTP_PORT`
- `keycloak/service.yaml` — ClusterIP, port 8080
- `keycloak/route.yaml` — OpenShift Route (host: optional)
- `postgres/configmap-init.yaml` — `init.sql` as ConfigMap
- `postgres/secret.yaml` — `POSTGRES_PASSWORD`, `POSTGRES_USER` (base64)
- `postgres/statefulset.yaml` — image `postgres:16-alpine`, mounts init ConfigMap at `/docker-entrypoint-initdb.d/`, PVC for `/var/lib/postgresql/data`
- `postgres/pvc.yaml` — 1Gi, ReadWriteOnce
- `postgres/service.yaml` — ClusterIP, port 5432
- `redpanda/deployment.yaml` — image `redpandadata/redpanda:v24.2.7`, dev-container command
- `redpanda/service.yaml` — named ports: `kafka` 9092, `schema-registry` 8081, `admin` 9644
- `redpanda/job-create-topics.yaml` — Job that runs `rpk topic create` for all 4 topics after Redpanda is ready
- `ibmmq/configmap-mqsc.yaml` — `config.mqsc` as ConfigMap
- `ibmmq/statefulset.yaml` — image `icr.io/ibm-mq/mq:9.3.5.1-r2`, mounts MQSC ConfigMap at `/etc/mqm/config.mqsc`, env: `LICENSE`, `MQ_QMGR_NAME`, `MQ_ADMIN_PASSWORD`, PVC for MQ data
- `ibmmq/pvc.yaml` — 1Gi, ReadWriteOnce
- `ibmmq/service.yaml` — named ports: `mq` 1414, `console` 9443
- `infra/otel/collector-config.yaml` — reference OTel Collector config (per TRACING.md §6)

## Unit Tests
N/A — YAML manifests. Verification is structural.

## Verification
```bash
# If kubectl available:
kubectl apply --dry-run=client -f infra/k8s/infra/ --recursive    # zero errors

# Always:
python3 -c "
import yaml, glob, sys
files = glob.glob('infra/k8s/infra/**/*.yaml', recursive=True)
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
- [ ] All 4 infra services represented with Deployment/StatefulSet + Service
- [ ] Secrets used for credentials (not ConfigMap)
- [ ] PVCs defined for stateful services (PostgreSQL, IBM MQ)
