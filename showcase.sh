#!/usr/bin/env bash
# showcase.sh — single-command control for the observability showcase stack
# Usage: ./showcase.sh {build|start|stop|restart|status|logs|smoke}

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
MVNW="$SCRIPT_DIR/mvnw"
DC_FILE="$SCRIPT_DIR/infra/docker/docker-compose.yml"
DC() { podman-compose -f "$DC_FILE" "$@"; }

# ── Colours ───────────────────────────────────────────────────────────────────
GRN='\033[0;32m'; YEL='\033[1;33m'; RED='\033[0;31m'
CYN='\033[0;36m'; BLD='\033[1m'; NC='\033[0m'

step()  { echo -e "\n${BLD}── $* ──${NC}"; }
log()   { echo -e "  ${CYN}◆${NC} $*"; }
ok()    { echo -e "  ${GRN}✓${NC} $*"; }
warn()  { echo -e "  ${YEL}!${NC} $*"; }
fail()  { echo -e "  ${RED}✗${NC} $*" >&2; }

# ── Build ─────────────────────────────────────────────────────────────────────

cmd_build() {
  step "Building Java modules"
  JAVA_HOME="$JAVA_HOME" "$MVNW" clean package -DskipTests
  ok "Maven build complete"

  step "Building container images"
  DC build
  ok "All images built"
}

jars_exist() {
  local missing=0
  for jar in \
    "$SCRIPT_DIR/apps/payment-gateway/target/payment-gateway-1.0.0-SNAPSHOT.jar" \
    "$SCRIPT_DIR/apps/transaction-engine/target/transaction-engine-1.0.0-SNAPSHOT.jar" \
    "$SCRIPT_DIR/apps/account-verifier/target/quarkus-app/quarkus-run.jar" \
    "$SCRIPT_DIR/apps/clearing-house/target/quarkus-app/quarkus-run.jar"
  do
    [[ -f "$jar" ]] || { fail "Missing: $jar"; missing=1; }
  done
  return $missing
}

# ── Start / Stop ──────────────────────────────────────────────────────────────

cmd_start() {
  if ! jars_exist; then
    warn "JARs missing — running build first..."
    cmd_build
  fi

  step "Starting stack"
  DC up -d

  step "Stack is up"
  echo ""
  echo -e "  ${BLD}Frontend     ${NC}  http://localhost:3000"
  echo -e "  ${BLD}API          ${NC}  http://localhost:8090"
  echo -e "  ${BLD}Keycloak     ${NC}  http://localhost:8080  (admin / admin)"
  echo -e "  ${BLD}IBM MQ Web   ${NC}  https://localhost:9443/ibmmq/console  (admin / admin)"
  echo ""
  echo -e "  Login as: ${BLD}testuser / password${NC}"
  echo ""
  echo -e "  Logs:     ./showcase.sh logs"
  echo -e "  Stop:     ./showcase.sh stop"
  echo ""
  warn "Services start in dependency order — the SPA may take ~90s to become healthy."
  warn "Run './showcase.sh status' to monitor progress."
  echo ""
}

cmd_stop() {
  step "Stopping stack"
  DC down
  ok "Everything stopped"
}

cmd_restart() {
  cmd_stop
  cmd_start
}

# ── Status / Logs ─────────────────────────────────────────────────────────────

cmd_status() {
  step "Container status"
  DC ps
}

cmd_logs() {
  local svc="${1:-}"
  if [[ -n "$svc" ]]; then
    DC logs -f "$svc"
  else
    DC logs -f
  fi
}

# ── Smoke test ────────────────────────────────────────────────────────────────

cmd_smoke() {
  step "Smoke test"
  log "Getting JWT from Keycloak..."
  local token
  token=$(curl -sf -X POST \
    "http://localhost:8080/realms/BankDemoRealm/protocol/openid-connect/token" \
    -d "client_id=spa-payment-client&grant_type=password&username=testuser&password=password" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
  ok "Token obtained (${#token} chars)"

  log "Submitting payment..."
  local resp txn_id
  resp=$(curl -sf -X POST http://localhost:8090/api/v1/payments \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d '{"sourceAccount":"ACC-001","destinationAccount":"ACC-002","amount":100.00,"currency":"USD"}')
  txn_id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['transactionId'])")
  ok "Payment accepted — txnId: $txn_id"

  log "Listening for SSE completion (up to 15s)..."
  local sse
  sse=$(curl -N -s --max-time 15 "http://localhost:8090/api/v1/payments/stream/$txn_id")
  if echo "$sse" | grep -q '"status":"COMPLETED"'; then
    ok "SSE received: $sse"
    echo ""
    ok "Full pipeline verified ✓"
  else
    fail "Expected COMPLETED status. Got: $sse"
    exit 1
  fi
}

# ── Dispatch ──────────────────────────────────────────────────────────────────
case "${1:-help}" in
  build)   cmd_build   ;;
  start)   cmd_start   ;;
  stop)    cmd_stop    ;;
  restart) cmd_restart ;;
  status)  cmd_status  ;;
  logs)    cmd_logs "${2:-}" ;;
  smoke)   cmd_smoke   ;;
  help|*)
    echo ""
    echo -e "  ${BLD}showcase.sh${NC} — observability showcase control script"
    echo ""
    echo "  Usage:  ./showcase.sh <command>"
    echo ""
    echo "  Commands:"
    echo "    build           Build JARs + container images (run once, then on code changes)"
    echo "    start           Start the full stack with podman-compose"
    echo "    stop            Stop everything"
    echo "    restart         stop + start"
    echo "    status          Show container status"
    echo "    logs [service]  Tail logs (all services, or a specific one)"
    echo "    smoke           End-to-end curl smoke test"
    echo ""
    echo "  Service names for 'logs': account-verifier, transaction-engine,"
    echo "    clearing-house, payment-gateway, spa-mobile-app, keycloak, ..."
    echo ""
    ;;
esac
