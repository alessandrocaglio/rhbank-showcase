# Stage 1: build
FROM registry.access.redhat.com/ubi9/nodejs-20 AS builder
WORKDIR /opt/app-root/src
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: serve
FROM registry.access.redhat.com/ubi9/nginx-124
COPY --from=builder /opt/app-root/src/dist /usr/share/nginx/html
COPY --from=builder /opt/app-root/src/public/config.template.js /usr/share/nginx/html/config.template.js
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 docker-entrypoint.sh /docker-entrypoint.d/40-env-config.sh
# OpenShift assigns a random UID with GID 0. Make html dir group-writable so
# the entrypoint can write config.js at runtime under any arbitrary UID.
USER 0
RUN chgrp -R 0 /usr/share/nginx/html && chmod -R g=u /usr/share/nginx/html
USER 1001
EXPOSE 8080
