# R22 — 🟠 K8s: Redpanda deployed as Deployment (no PVC) — all data lost on pod restart

## Problem
`infra/k8s/infra/redpanda/deployment.yaml` deploys Redpanda as a `Deployment` with no
`PersistentVolumeClaim`. A pod restart (node drain, OOM kill, cluster upgrade) wipes all Kafka
topic data, committed offsets, and any in-flight messages. After any restart, the demo pipeline
requires a full restart from scratch and previously committed consumer group offsets are gone.

## Files to change
- `infra/k8s/infra/redpanda/deployment.yaml` → rename / replace with `statefulset.yaml`
- Add `infra/k8s/infra/redpanda/pvc.yaml`

## Fix
Convert to a `StatefulSet` with a `volumeClaimTemplate`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redpanda
spec:
  serviceName: redpanda
  replicas: 1
  volumeClaimTemplates:
    - metadata:
        name: redpanda-data
      spec:
        accessModes: [ReadWriteOnce]
        resources:
          requests:
            storage: 5Gi
  template:
    spec:
      containers:
        - name: redpanda
          volumeMounts:
            - name: redpanda-data
              mountPath: /var/lib/redpanda/data
```

Also add a headless Service for the StatefulSet.

## Acceptance
- Redpanda pod restarted → topics and committed offsets survive
- `rpk cluster health` returns healthy after restart
