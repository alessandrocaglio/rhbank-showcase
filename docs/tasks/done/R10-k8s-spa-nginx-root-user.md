# R10 — 🔴 K8s: spa-mobile-app Nginx runs as root — blocked by OpenShift default SCC

## Problem
`apps/spa-mobile-app/Dockerfile` uses `FROM docker.io/nginx:1.27-alpine`. The official Nginx
image's master process runs as root (UID 0). OpenShift's default `restricted` SCC forbids root
containers and requires an arbitrary UID. The SPA pod will enter `CrashLoopBackOff` on a standard
OpenShift cluster.

## File to change
- `apps/spa-mobile-app/Dockerfile`

## Fix
Replace the base image with `nginxinc/nginx-unprivileged` which runs on port 8080 as UID 101:

```dockerfile
# Stage 2: serve
FROM docker.io/nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY --from=builder /app/public/config.template.js /usr/share/nginx/html/config.template.js
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/40-env-config.sh
RUN chmod +x /docker-entrypoint.d/40-env-config.sh
EXPOSE 8080
```

Also update `nginx.conf` to `listen 8080;` and update the K8s Service/Route and docker-compose
port mappings accordingly (`3000:8080` instead of `3000:80`).

## Acceptance
- SPA pod starts successfully on OpenShift with default restricted SCC
- `oc get pod -l app=spa-mobile-app` shows `1/1 Running`
- SPA is accessible via the Route
