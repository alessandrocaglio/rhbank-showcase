# T22 · Run the complete project locally (end-to-end smoke test)

**Phase:** 8 — Local integration
**Status:** todo
**Depends on:** T03 (infra), T11 (gateway Kafka), T16 (clearing Kafka), T19 (SPA SSE)

## Goal

Bring the entire stack up on a single machine and confirm a payment travels the full
pipeline — HTTP → gRPC → Kafka → JMS → Kafka → SSE — with every hop visible as a
single unbroken trace in Grafana Tempo.

## Pre-flight checks

- [ ] Java 21 JDK available: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`
- [ ] Docker or Podman available: `docker-compose --version`
- [ ] Node.js 20 LTS available: `node --version`
- [ ] All Java modules built: `./mvnw clean package -DskipTests` exits 0
- [ ] All frontend deps installed: `cd apps/spa-mobile-app && npm install`

## Startup sequence

### Step 1 — Infrastructure

```bash
docker-compose -f infra/docker/docker-compose.yml up -d

# Wait until all four infra services are healthy (~60 s for Keycloak, ~45 s for IBM MQ)
docker-compose -f infra/docker/docker-compose.yml ps
```

All services must show `healthy` (or `exited (0)` for `redpanda-init`) before proceeding.

### Step 2 — Backend services (four separate terminals)

```bash
# Terminal 1 — account-verifier  (gRPC :9090 / health :8080)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/account-verifier quarkus:dev -Ddebug=false

# Terminal 2 — transaction-engine  (management :8082)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/transaction-engine spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dserver.port=8082"

# Terminal 3 — clearing-house  (health :8083)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/clearing-house quarkus:dev \
  -Dquarkus.http.port=8083 -Ddebug=false

# Terminal 4 — payment-gateway  (HTTP :8090)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./mvnw -pl apps/payment-gateway spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
    -Dserver.port=8090 \
    -Dgrpc.client.account-verifier.address=static://localhost:9090 \
    -Dgrpc.client.account-verifier.negotiation-type=plaintext"
```

### Step 3 — Frontend

```bash
cd apps/spa-mobile-app && npm run dev   # http://localhost:5173
```

## Triggering the full pipeline

### Option A — Browser (with frontend)

1. Open **http://localhost:5173**.
2. Log in as **`testuser` / `password`**.
3. Submit a payment: `ACC-001` → `ACC-002`, amount ≤ `10 000`.
4. The status page should update to **COMPLETED** within 5 seconds via SSE.

### Option B — curl (headless)

```bash
# 1. Get a token from Keycloak
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/BankDemoRealm/protocol/openid-connect/token" \
  -d "client_id=spa-payment-client&grant_type=password&username=testuser&password=password" \
  | jq -r .access_token)

# 2. Open an SSE stream (background) — replace TX_ID after the next step
#    Subscribe first, as the gateway resolves the emitter on completion.
TXN_ID=$(curl -s -X POST http://localhost:8090/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccount":"ACC-001","destinationAccount":"ACC-002","amount":250.00,"currency":"USD"}' \
  | jq -r .transactionId)

echo "Transaction ID: $TXN_ID"

# 3. Listen for the SSE result (blocks until COMPLETED/FAILED arrives)
curl -N http://localhost:8090/api/v1/payments/stream/$TXN_ID
```

## Acceptance criteria

- [ ] `POST /api/v1/payments` returns **HTTP 202** with a non-null `transactionId`
- [ ] SSE stream delivers `"status":"COMPLETED"` within **5 seconds**
- [ ] `accounts_db.accounts` — `ACC-001` balance decremented by the payment amount
- [ ] `ledger_db.transaction_ledger` — a row exists for the `transactionId`
- [ ] (Optional) Grafana Tempo — a single trace spans all five services

## Database verification

```bash
# Check ACC-001 balance was deducted
docker exec -it $(docker ps -qf name=postgres) \
  psql -U postgres -d accounts_db \
  -c "SELECT account_id, balance FROM accounts WHERE account_id = 'ACC-001';"

# Check ledger record was created
docker exec -it $(docker ps -qf name=postgres) \
  psql -U postgres -d ledger_db \
  -c "SELECT transaction_id, status, amount, created_at FROM transaction_ledger ORDER BY created_at DESC LIMIT 3;"
```

## Teardown

```bash
# Stop background Java processes
kill $(lsof -ti:8090,8083,8082,9090) 2>/dev/null || true

# Stop and remove all infrastructure containers and volumes
docker-compose -f infra/docker/docker-compose.yml down -v
```

## Notes

- Start services in the order shown: `account-verifier` first (gRPC server must be up before the gateway calls it), then `transaction-engine` and `clearing-house` (Kafka consumers), then `payment-gateway`.
- `clearing-house` connects to IBM MQ on startup. If MQ is not yet healthy, it will retry automatically every 5 seconds — no manual action needed.
- To test a **rejection** path, use `ACC-003` (zero balance) or `ACC-004` (suspended) as the source account. The SSE stream will deliver `"status":"FAILED"`.
