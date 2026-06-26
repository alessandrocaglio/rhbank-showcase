#!/bin/sh
set -e
envsubst < /usr/share/nginx/html/config.template.js > /usr/share/nginx/html/config.js
echo "Generated config.js from environment variables"
