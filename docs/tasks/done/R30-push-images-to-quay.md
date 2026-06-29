# R30 — Push Application Images to quay.io

## Goal
Build and push all five application container images to `quay.io/acaglio/` using Podman so that
the OpenShift Helm charts can pull them. Repos are **public** — no ImagePullSecret required.

A future CI pipeline will automate this (see R33). For now, push is done manually from the dev machine.

## Images to push

| Module | Dockerfile | Image tag |
|---|---|---|
| `apps/payment-gateway` | `apps/payment-gateway/Dockerfile` | `quay.io/acaglio/payment-gateway:latest` |
| `apps/account-verifier` | `apps/account-verifier/Dockerfile` | `quay.io/acaglio/account-verifier:latest` |
| `apps/transaction-engine` | `apps/transaction-engine/Dockerfile` | `quay.io/acaglio/transaction-engine:latest` |
| `apps/clearing-house` | `apps/clearing-house/Dockerfile` | `quay.io/acaglio/clearing-house:latest` |
| `apps/spa-mobile-app` | `apps/spa-mobile-app/Dockerfile` | `quay.io/acaglio/spa-mobile-app:latest` |

## Build approach — Podman + Dockerfile

### Prerequisites
1. Maven build must have already produced the fat-jars / quarkus-app dirs: `./mvnw clean package -DskipTests`
2. `podman login quay.io` with quay.io credentials (done once per session).
3. Verify each Dockerfile copies the OTel agent JAR to `/opt/otel/opentelemetry-javaagent.jar`
   (this is the path all K8s Deployments reference via `JAVA_TOOL_OPTIONS`).

### Build and push sequence
```bash
./mvnw clean package -DskipTests

for svc in payment-gateway account-verifier transaction-engine clearing-house; do
  podman build -t quay.io/acaglio/$svc:latest apps/$svc/
  podman push quay.io/acaglio/$svc:latest
done

podman build -t quay.io/acaglio/spa-mobile-app:latest apps/spa-mobile-app/
podman push quay.io/acaglio/spa-mobile-app:latest
```

## showcase.sh integration
Add a `push` subcommand to `showcase.sh` that runs the above sequence:
```bash
./showcase.sh push          # build all images and push to quay.io
./showcase.sh push payment-gateway   # push a single service
```

## OTel agent check
Before pushing, verify each Java Dockerfile includes a step like:
```dockerfile
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.x.x/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
```
or copies it from a local path. The agent JAR must be baked into the image, not volume-mounted.

## Acceptance
- `podman pull quay.io/acaglio/payment-gateway:latest` succeeds without credentials.
- All 5 images visible in quay.io/acaglio/ repository list.
- No `ImagePullBackOff` when the Helm chart deploys to OpenShift.

## Related
- R33 — GitHub Actions CI pipeline (automates this push on every commit to main)
