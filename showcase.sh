#!/usr/bin/env bash
# showcase.sh — single-command control for the observability showcase stack
# Usage: ./showcase.sh {build|start|stop|restart|status|logs|smoke|test|push}

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
  local service="${1:-}"
  local java_services=(payment-gateway account-verifier transaction-engine clearing-house)
  local valid_services=(payment-gateway account-verifier transaction-engine clearing-house spa-mobile-app)

  if [[ -n "$service" ]]; then
    # Validate service name
    local found=0
    for svc in "${valid_services[@]}"; do
      [[ "$svc" == "$service" ]] && { found=1; break; }
    done
    if [[ $found -eq 0 ]]; then
      fail "Unknown service: '$service'"
      echo "  Valid names: ${valid_services[*]}" >&2
      exit 1
    fi

    # Determine if it is a Java service
    local is_java=0
    for svc in "${java_services[@]}"; do
      [[ "$svc" == "$service" ]] && { is_java=1; break; }
    done

    if [[ $is_java -eq 1 ]]; then
      step "Building Java module: $service"
      JAVA_HOME="$JAVA_HOME" "$MVNW" clean package -pl "apps/$service" -DskipTests
      ok "Maven build complete"
    fi

    step "Building container image: $service"
    DC build "$service"
    ok "Image built: $service"
  else
    # Full build — existing behaviour
    step "Building Java modules"
    JAVA_HOME="$JAVA_HOME" "$MVNW" clean package -DskipTests
    ok "Maven build complete"

    step "Building container images"
    DC build
    ok "All images built"
  fi
}

jars_exist() {
  # Optional first argument: a single Java service name.
  # When provided, checks only that service's JAR; when absent, checks all four.
  local filter="${1:-}"
  local missing=0

  declare -A JAR_MAP=(
    [payment-gateway]="$SCRIPT_DIR/apps/payment-gateway/target/payment-gateway-1.0.0-SNAPSHOT.jar"
    [transaction-engine]="$SCRIPT_DIR/apps/transaction-engine/target/transaction-engine-1.0.0-SNAPSHOT.jar"
    [account-verifier]="$SCRIPT_DIR/apps/account-verifier/target/quarkus-app/quarkus-run.jar"
    [clearing-house]="$SCRIPT_DIR/apps/clearing-house/target/quarkus-app/quarkus-run.jar"
  )

  if [[ -n "$filter" ]]; then
    local jar="${JAR_MAP[$filter]:-}"
    [[ -f "$jar" ]] || { fail "Missing: $jar"; missing=1; }
  else
    for jar in "${JAR_MAP[@]}"; do
      [[ -f "$jar" ]] || { fail "Missing: $jar"; missing=1; }
    done
  fi

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
  step "Stack Status"

  # Get all container statuses in one call
  local ps_output
  ps_output=$(podman ps --all --format '{{.Names}}\t{{.Status}}' 2>/dev/null) || true

  # Helper: look up a container's status string
  get_status() {
    echo "$ps_output" | grep "^$1"$'\t' | cut -f2
  }

  # Helper: pick icon based on status string
  status_icon() {
    local s="$1"
    if [[ "$s" == *"(healthy)"* ]]; then
      echo "🟢"
    elif [[ "$s" == *"(unhealthy)"* ]] || [[ "$s" == *"Exited"* ]] || [[ -z "$s" ]]; then
      echo "🔴"
    else
      echo "🟡"
    fi
  }

  # Print one service row: CONTAINER_NAME LABEL [URL]
  print_svc() {
    local name="$1" label="$2" url="${3:-}"
    local sts icon
    sts=$(get_status "$name")
    icon=$(status_icon "$sts")
    local display="${sts:-not running}"
    if [[ -n "$url" ]]; then
      printf "  %s  %-22s %-38s %s\n" "$icon" "$label" "$display" "$url"
    else
      printf "  %s  %-22s %s\n" "$icon" "$label" "$display"
    fi
  }

  # Counters
  local healthy=0 starting=0 unhealthy=0 stopped=0

  count_status() {
    local s
    s=$(get_status "$1")
    if [[ "$s" == *"(healthy)"* ]]; then
      (( healthy++ )) || true
    elif [[ "$s" == *"(unhealthy)"* ]] || [[ "$s" == *"Exited"* ]]; then
      (( unhealthy++ )) || true
    elif [[ -z "$s" ]]; then
      (( stopped++ )) || true
    else
      (( starting++ )) || true
    fi
  }

  echo ""
  echo -e "  ${BLD}Infrastructure${NC}"
  print_svc docker_keycloak_1  "Keycloak"         "http://localhost:8080"
  print_svc docker_postgres_1  "PostgreSQL"       ""
  print_svc docker_redpanda_1  "Redpanda (Kafka)" ""
  print_svc docker_ibmmq_1     "IBM MQ"           "https://localhost:9443/ibmmq/console"

  echo ""
  echo -e "  ${BLD}Application Services${NC}"
  print_svc docker_account-verifier_1   "account-verifier"   "http://localhost:8085/q/health"
  print_svc docker_transaction-engine_1 "transaction-engine"  ""
  print_svc docker_clearing-house_1     "clearing-house"     "http://localhost:8083/q/health"
  print_svc docker_payment-gateway_1    "payment-gateway"    "http://localhost:8090/actuator/health"
  print_svc docker_spa-mobile-app_1     "spa-mobile-app"     "http://localhost:3000"

  # Tally totals
  for c in docker_keycloak_1 docker_postgres_1 docker_redpanda_1 docker_ibmmq_1 \
            docker_account-verifier_1 docker_transaction-engine_1 \
            docker_clearing-house_1 docker_payment-gateway_1 docker_spa-mobile-app_1; do
    count_status "$c"
  done

  echo ""
  echo -e "  ${GRN}${healthy} healthy${NC} · ${YEL}${starting} starting${NC} · ${RED}${unhealthy} unhealthy${NC} · ${RED}${stopped} stopped${NC}"
  echo ""
  echo -e "  Browser: ${BLD}http://localhost:3000${NC}  (testuser / password)"
  echo ""
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

# ── Test ──────────────────────────────────────────────────────────────────────

cmd_test() {
  local module="${1:-}"

  # Map of friendly name → Maven module path (or "spa" for npm)
  declare -A MODULE_MAP=(
    [grpc-api]="apps/grpc-api"
    [account-verifier]="apps/account-verifier"
    [payment-gateway]="apps/payment-gateway"
    [transaction-engine]="apps/transaction-engine"
    [clearing-house]="apps/clearing-house"
    [spa]="__spa__"
    [spa-mobile-app]="__spa__"
  )

  # ── Single-module mode ────────────────────────────────────────────────────
  if [[ -n "$module" ]]; then
    if [[ -z "${MODULE_MAP[$module]+x}" ]]; then
      fail "Unknown module: '$module'"
      echo "  Valid names: grpc-api, account-verifier, payment-gateway, transaction-engine, clearing-house, spa" >&2
      exit 1
    fi

    local target="${MODULE_MAP[$module]}"
    step "Running tests — $module"

    if [[ "$target" == "__spa__" ]]; then
      log "Testing spa-mobile-app..."
      if (cd "$SCRIPT_DIR/apps/spa-mobile-app" && npm test 2>/dev/null); then
        ok "spa-mobile-app — passed"
      else
        fail "spa-mobile-app — FAILED"
        (cd "$SCRIPT_DIR/apps/spa-mobile-app" && npm test 2>&1) | tail -20
        exit 1
      fi
    else
      log "Testing $module..."
      if JAVA_HOME="$JAVA_HOME" "$MVNW" test -pl "$target" -q 2>/dev/null; then
        ok "$module — passed"
      else
        fail "$module — FAILED"
        JAVA_HOME="$JAVA_HOME" "$MVNW" test -pl "$target" 2>&1 | grep -E "FAIL|ERROR|Tests run" | tail -20
        exit 1
      fi
    fi
    return 0
  fi

  # ── All-modules mode ──────────────────────────────────────────────────────
  step "Running tests"

  local java_modules=("apps/grpc-api" "apps/account-verifier" "apps/payment-gateway" "apps/transaction-engine" "apps/clearing-house")
  local java_labels=("grpc-api" "account-verifier" "payment-gateway" "transaction-engine" "clearing-house")
  local passed=0
  local failed_modules=()

  for i in "${!java_modules[@]}"; do
    local mod="${java_modules[$i]}"
    local label="${java_labels[$i]}"
    log "Testing $label..."
    if JAVA_HOME="$JAVA_HOME" "$MVNW" test -pl "$mod" -q 2>/dev/null; then
      ok "$label — passed"
      (( passed++ )) || true
    else
      fail "$label — FAILED"
      failed_modules+=("$label")
      JAVA_HOME="$JAVA_HOME" "$MVNW" test -pl "$mod" 2>&1 | grep -E "FAIL|ERROR|Tests run" | tail -20
    fi
  done

  log "Testing spa-mobile-app..."
  if (cd "$SCRIPT_DIR/apps/spa-mobile-app" && npm test 2>/dev/null); then
    ok "spa-mobile-app — passed"
    (( passed++ )) || true
  else
    fail "spa-mobile-app — FAILED"
    failed_modules+=("spa-mobile-app")
    (cd "$SCRIPT_DIR/apps/spa-mobile-app" && npm test 2>&1) | tail -20
  fi

  echo ""
  if [[ ${#failed_modules[@]} -eq 0 ]]; then
    ok "All tests passed ($passed modules)"
    return 0
  else
    fail "${#failed_modules[@]} module(s) failed: ${failed_modules[*]}"
    return 1
  fi
}

# ── Push ──────────────────────────────────────────────────────────────────────

cmd_push() {
  local target="${1:-all}"
  local all_images=(payment-gateway account-verifier transaction-engine clearing-house spa-mobile-app)
  local images=()

  if [[ "$target" == "all" ]]; then
    images=("${all_images[@]}")
  else
    # Validate the requested service name
    local found=0
    for svc in "${all_images[@]}"; do
      if [[ "$svc" == "$target" ]]; then
        found=1
        break
      fi
    done
    if [[ $found -eq 0 ]]; then
      fail "Unknown service: '$target'"
      echo "  Valid names: ${all_images[*]}" >&2
      exit 1
    fi
    images=("$target")
  fi

  # Derive version tag from git short hash
  local git_hash
  git_hash=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")
  log "Image version tag: ${git_hash}"

  # Ensure JARs exist for Java services before building images
  local java_services=(payment-gateway account-verifier transaction-engine clearing-house)

  if [[ "$target" == "all" ]]; then
    # Full check: any Java service missing triggers a full build
    local need_build=0
    for svc in "${images[@]}"; do
      for jsvc in "${java_services[@]}"; do
        if [[ "$svc" == "$jsvc" ]]; then
          need_build=1
          break
        fi
      done
      [[ $need_build -eq 1 ]] && break
    done

    if [[ $need_build -eq 1 ]] && ! jars_exist 2>/dev/null; then
      warn "JARs missing — running Maven build first..."
      step "Building Java modules"
      JAVA_HOME="$JAVA_HOME" "$MVNW" clean package -DskipTests
      ok "Maven build complete"
    fi
  else
    # Single-service push: check and build only the targeted Java service (if applicable)
    local is_java=0
    for jsvc in "${java_services[@]}"; do
      [[ "$jsvc" == "$target" ]] && { is_java=1; break; }
    done

    if [[ $is_java -eq 1 ]] && ! jars_exist "$target" 2>/dev/null; then
      warn "JAR missing for $target — running targeted Maven build first..."
      step "Building Java module: $target"
      JAVA_HOME="$JAVA_HOME" "$MVNW" clean package -pl "apps/$target" -DskipTests
      ok "Maven build complete"
    fi
  fi

  local built=() failed_build=() failed_push=()

  for svc in "${images[@]}"; do
    local img_latest="quay.io/acaglio/${svc}:latest"
    local img_versioned="quay.io/acaglio/${svc}:${git_hash}"
    step "Building ${img_versioned}"
    # Build with the versioned tag, then also tag as latest
    if podman build \
        --tag "${img_versioned}" \
        --tag "${img_latest}" \
        "$SCRIPT_DIR/apps/$svc/"; then
      ok "$svc image built (${git_hash} + latest)"
      built+=("$svc")
    else
      fail "podman build failed for $svc"
      failed_build+=("$svc")
    fi
  done

  if [[ ${#built[@]} -gt 0 ]]; then
    step "Pushing images to quay.io/acaglio"
    for svc in "${built[@]}"; do
      local img_latest="quay.io/acaglio/${svc}:latest"
      local img_versioned="quay.io/acaglio/${svc}:${git_hash}"
      log "Pushing $svc (${git_hash})..."
      if podman push "${img_versioned}" && podman push "${img_latest}"; then
        ok "$svc pushed (${git_hash} + latest)"
      else
        fail "podman push failed for $svc"
        failed_push+=("$svc")
      fi
    done
  fi

  echo ""
  if [[ ${#failed_build[@]} -eq 0 && ${#failed_push[@]} -eq 0 ]]; then
    ok "All images built and pushed (${#built[@]} services, tag: ${git_hash})"
  else
    [[ ${#failed_build[@]} -gt 0 ]] && fail "Build failed: ${failed_build[*]}"
    [[ ${#failed_push[@]} -gt 0 ]] && fail "Push failed: ${failed_push[*]}"
    exit 1
  fi
}

# ── Dispatch ──────────────────────────────────────────────────────────────────
case "${1:-help}" in
  build)   cmd_build "${2:-}" ;;
  start)   cmd_start   ;;
  stop)    cmd_stop    ;;
  restart) cmd_restart ;;
  status)  cmd_status  ;;
  logs)    cmd_logs "${2:-}" ;;
  smoke)   cmd_smoke   ;;
  test)    cmd_test "${2:-}"   ;;
  push)    cmd_push "${2:-all}" ;;
  help|*)
    echo ""
    echo -e "  ${BLD}showcase.sh${NC} — observability showcase control script"
    echo ""
    echo "  Usage:  ./showcase.sh <command>"
    echo ""
    echo "  Commands:"
    echo "    build [service]         Build JARs + container image (all services, or one: payment-gateway, etc.)"
    echo "    start               Start the full stack with podman-compose"
    echo "    stop                Stop everything"
    echo "    restart             stop + start"
    echo "    status              Show container status"
    echo "    logs [service]      Tail logs (all services, or a specific one)"
    echo "    smoke               End-to-end curl smoke test"
    echo "    test [module]       Run unit tests (all modules, or one: account-verifier, payment-gateway, etc.)"
    echo "    push [service]      Build + push images to quay.io/acaglio (all, or a single service)"
    echo ""
    echo "  Service names for 'logs': account-verifier, transaction-engine,"
    echo "    clearing-house, payment-gateway, spa-mobile-app, keycloak, ..."
    echo ""
    ;;
esac
