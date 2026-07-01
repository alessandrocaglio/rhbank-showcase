# Stage 1: build
FROM registry.access.redhat.com/ubi9/nodejs-20 AS builder
WORKDIR /opt/app-root/src
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: serve — ubi9-minimal with nginx from RHEL repos (not S2I)
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

RUN microdnf install -y nginx gettext && microdnf clean all && \
    # Remove 'user nginx;' so nginx runs as whatever UID the container is given
    sed -i '/^user /d' /etc/nginx/nginx.conf && \
    # Write PID to /tmp — always writable regardless of UID
    sed -i 's|pid .*;|pid /tmp/nginx.pid;|' /etc/nginx/nginx.conf && \
    # Strip the embedded port-80 server block from the main nginx.conf
    sed -i '/^    server {/,/^    }$/d' /etc/nginx/nginx.conf && \
    # Remove all package-installed server configs; ours (port 8080) is copied in next
    rm -f /etc/nginx/conf.d/*.conf && \
    mkdir -p /docker-entrypoint.d && \
    # UID 1001 as owner (local podman), GID 0 as group (OpenShift supplemental group)
    chown -R 1001:0 /var/log/nginx /var/lib/nginx /usr/share/nginx/html /etc/nginx/conf.d && \
    chmod -R g=u    /var/log/nginx /var/lib/nginx /usr/share/nginx/html /etc/nginx/conf.d

COPY --from=builder /opt/app-root/src/dist /usr/share/nginx/html
COPY --from=builder /opt/app-root/src/public/config.template.js /usr/share/nginx/html/config.template.js
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 docker-entrypoint.sh /docker-entrypoint.d/40-env-config.sh

# Re-apply after COPY (COPY resets ownership to root:root)
RUN chown -R 1001:0 /usr/share/nginx/html /etc/nginx/conf.d && \
    chmod -R g=u    /usr/share/nginx/html /etc/nginx/conf.d

USER 1001
EXPOSE 8080
CMD ["/bin/sh", "-c", "/docker-entrypoint.d/40-env-config.sh && exec nginx -g 'daemon off;'"]
