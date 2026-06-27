# Session Checkpoint — 2026-06-27 (Updated)

## Status: ALL TASKS COMPLETE ✅ — SMOKE TEST PASSED

### Implementation (T01–T21 — ALL COMPLETE ✅)
All 21 implementation tasks are done and verified. The full monorepo is implemented:

| Module | Status |
|---|---|
| `grpc-api` (shared proto stubs) | ✅ Built, 3 tests |
| `account-verifier` (Quarkus 3.15.1) | ✅ 17 tests, JaCoCo ≥ 80% |
| `payment-gateway` (Spring Boot 3.3.5) | ✅ 27 tests, JaCoCo ≥ 80% |
| `transaction-engine` (Spring Boot 3.3.5) | ✅ 8 tests, JaCoCo ≥ 80% |
| `clearing-house` (Quarkus 3.15.1) | ✅ 13 tests (new: 2 from MqProperties Optional fix), JaCoCo ≥ 80% |
| `spa-mobile-app` (Vue 3 + Vite) | ✅ 19 Vitest tests |
| Docker Compose infra stack | ✅ Keycloak, PostgreSQL, Redpanda, IBM MQ |
| Kubernetes manifests (infra) | ✅ 19 YAML files validated |
| Kubernetes manifests (apps) | ✅ 13 YAML files validated |

### Smoke Test (T22) — COMPLETE ✅

**Full pipeline verified:** HTTP → gRPC → Kafka → IBM MQ → Kafka → SSE

Sample output:
```
TXN_ID: dcdd18e5-7326-45b5-8c74-75a1dab25bbd
data:{"transactionId":"dcdd18e5-7326-45b5-8c74-75a1dab25bbd","status":"COMPLETED","timestamp":"2026-06-27T13:21:03.078009176Z","detail":"Clearing successful"}
```

---

## All Bugs Fixed in This Session

### 1. IBM MQ Image (docker-compose.yml)
- **Was:** `docker.io/ibmcom/mq:latest` (4 years old, incompatible with jakarta.jms client)
- **Fix:** Changed to `icr.io/ibm-messaging/mq:9.3.5.1-r2`
- **File:** `infra/docker/docker-compose.yml`

### 2. IBM MQ MCAUSER (config.mqsc)
- **Was:** `MCAUSER('mqm')` — `mqm` is blocked from remote client connections in newer IBM MQ images
- **Fix:** Changed to `MCAUSER('app')` — the `app` user has full access to `DEV.*` resources in the developer image
- **File:** `infra/docker/config.mqsc`

### 3. clearing-house MqProperties empty-string config (Quarkus bug)
- **Was:** `String user`/`String password` with `@ConfigProperty(defaultValue = "")` — SmallRye Config 3.x rejects empty strings for non-Optional types
- **Fix:** Changed to `Optional<String>` with `.orElse("")` in getters
- **Files:** `apps/clearing-house/src/main/java/com/showcase/clearing/config/MqProperties.java`, `MqPropertiesTest.java`

### 4. clearing-house MqMessageListener not starting (@Startup)
- **Was:** `MqMessageListener` is `@ApplicationScoped` but never injected anywhere — CDI lazy init means `@PostConstruct` never fires
- **Fix:** Added `@Startup` annotation to force eager initialization at application boot
- **File:** `apps/clearing-house/src/main/java/com/showcase/clearing/messaging/MqMessageListener.java`

### 5. clearing-house test port conflict (Quarkus test)
- **Was:** `PaymentCompletedPublisherImplTest` (@QuarkusTest) tries to start on port 8081 which conflicts with Redpanda schema-registry when infra is running
- **Fix:** Added `quarkus.http.test-port=0` (random port for tests)
- **File:** `apps/clearing-house/src/main/resources/application.properties`

### 6. account-verifier Quarkus DevServices in dev mode
- **Was:** `quarkus:dev` mode tries to pull Docker images for DevServices → 403 from registries
- **Fix:** Pass `-Dquarkus.devservices.enabled=false -Dquarkus.kafka.devservices.enabled=false` on launch command line

### Previously Fixed (from prior session)
- `AccountServiceGrpcImpl.java`: `vertx.executeBlocking()` to offload gRPC handler off IO thread
- `PaymentEventPublisherImpl.java`: `@Transactional(TxType.NOT_SUPPORTED)` on `publishApproved()`
- `transaction-engine/pom.xml`: Added `flyway-database-postgresql` for Flyway 10.x
- `payment-realm.json`: Removed `postLogoutRedirectUris`, added `directAccessGrantsEnabled: true`

---

## Run Commands (Verified Working)

### Start Infrastructure
```bash
cd /home/dev/Documents/redhat/labs/showcase
podman-compose -f infra/docker/docker-compose.yml up -d
# Wait ~60s for Keycloak to finish importing realm
```

### Start Backend Services
```bash
mkdir -p /tmp/showcase-logs
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw -pl apps/account-verifier quarkus:dev \
  -Ddebug=false -Dquarkus.http.port=8085 \
  -Dquarkus.grpc.server.use-separate-server=false \
  -Dquarkus.devservices.enabled=false \
  -Dquarkus.kafka.devservices.enabled=false \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/accounts_db \
  -Dquarkus.datasource.username=appuser -Dquarkus.datasource.password=apppassword \
  -Dmp.messaging.outgoing.payment-approved.bootstrap.servers=localhost:9092 \
  -Dmp.messaging.outgoing.payment-approved-dead-letter.bootstrap.servers=localhost:9092 \
  > /tmp/showcase-logs/account-verifier.log 2>&1 &

JAVA=/usr/lib/jvm/java-21-openjdk/bin/java
BASE=$(pwd)/apps
LOGS=/tmp/showcase-logs

$JAVA -Dserver.port=8088 \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/ledger_db \
  -Dspring.datasource.username=appuser -Dspring.datasource.password=apppassword \
  -Dspring.kafka.bootstrap-servers=localhost:9092 \
  -Dibm.mq.conn-name=localhost\(1414\) -Dibm.mq.queue-manager=QM1 \
  -Dibm.mq.channel=DEV.APP.SVRCONN \
  -Dapp.mq.queues.clearing=DEV.QUEUE.CLEARING \
  -jar $BASE/transaction-engine/target/transaction-engine-1.0.0-SNAPSHOT.jar \
  > $LOGS/transaction-engine.log 2>&1 &

$JAVA -Dquarkus.http.port=8083 \
  -Dclearing.mq.host=localhost -Dclearing.mq.port=1414 \
  -Dclearing.mq.queue-manager=QM1 -Dclearing.mq.channel=DEV.APP.SVRCONN \
  -Dclearing.mq.queue-name=DEV.QUEUE.CLEARING \
  -Dclearing.simulation.delay-ms.min=100 -Dclearing.simulation.delay-ms.max=500 \
  -Dmp.messaging.outgoing.payment-completed.bootstrap.servers=localhost:9092 \
  -Dmp.messaging.outgoing.payment-completed-dead-letter.bootstrap.servers=localhost:9092 \
  -jar $BASE/clearing-house/target/quarkus-app/quarkus-run.jar \
  > $LOGS/clearing-house.log 2>&1 &

$JAVA -Dserver.port=8090 \
  -Dspring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/BankDemoRealm \
  -Dspring.kafka.bootstrap-servers=localhost:9092 \
  -Dspring.kafka.consumer.group-id=payment-gateway-group \
  -Dgrpc.client.account-verifier.address=static://localhost:8085 \
  -Dgrpc.client.account-verifier.negotiation-type=plaintext \
  -jar $BASE/payment-gateway/target/payment-gateway-1.0.0-SNAPSHOT.jar \
  > $LOGS/payment-gateway.log 2>&1 &
```

### Smoke Test (curl)
```bash
TOKEN=$(curl -sf -X POST \
  "http://localhost:8080/realms/BankDemoRealm/protocol/openid-connect/token" \
  -d "client_id=spa-payment-client&grant_type=password&username=testuser&password=password" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Submit payment
RESPONSE=$(curl -sf -X POST http://localhost:8090/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccount":"ACC-001","destinationAccount":"ACC-002","amount":100.00,"currency":"USD"}')
TXN_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['transactionId'])")
echo "TXN: $TXN_ID"

# Listen for SSE (immediately after submit — pipeline completes in ~1-2s)
curl -N -s --max-time 15 "http://localhost:8090/api/v1/payments/stream/$TXN_ID"
```

---

## Port Map (all on localhost)

| Service | Port | Notes |
|---|---|---|
| Keycloak | 8080 | container |
| PostgreSQL | 5432 | container |
| Redpanda (Kafka) | 9092 | container |
| IBM MQ | 1414, 9443 | container — `icr.io/ibm-messaging/mq:9.3.5.1-r2` |
| account-verifier HTTP+gRPC | 8085 | dev mode (quarkus:dev); gRPC on same port |
| transaction-engine | 8088 | headless; Kafka consumer + MQ producer |
| clearing-house | 8083 | production jar |
| payment-gateway | 8090 | production jar |

---

## Containerisation (2026-06-27 — second session)

All services now run as podman containers. `./showcase.sh build && ./showcase.sh start` brings up the full 9-container stack. `./showcase.sh stop` tears it all down.

### New files
- `apps/payment-gateway/Dockerfile` — Spring Boot fat-jar (UBI9 openjdk-21-runtime)
- `apps/account-verifier/Dockerfile` — Quarkus fast-jar (UBI9 openjdk-21-runtime)
- `apps/transaction-engine/Dockerfile` — Spring Boot fat-jar
- `apps/clearing-house/Dockerfile` — Quarkus fast-jar
- `apps/account-verifier/src/main/proto/account.proto` — fixes gRPC server in production-jar mode

### Modified files
- `apps/account-verifier/pom.xml` — removed grpc-api dependency (stubs now generated locally from proto)
- `apps/payment-gateway/src/main/java/.../config/SecurityConfig.java` — added CORS for `http://localhost:3000`
- `apps/payment-gateway/src/main/java/.../service/SseEmitterServiceImpl.java` — CompletableFuture bridge (fixes race condition)
- `apps/spa-mobile-app/public/config.template.js` — changed `__VARNAME__` to `${VARNAME}` for envsubst
- `infra/docker/docker-compose.yml` — added 5 service containers; fixed Keycloak healthcheck (bash /dev/tcp); fixed transaction-engine healthcheck (kill -0 1)
- `showcase.sh` — now uses `podman-compose up/down` for everything; no more host Java processes

### JWT issuer fix
- Keycloak issues tokens with `iss: http://localhost:8080/...` (browser-visible URL)
- payment-gateway uses BOTH env vars: `ISSUER_URI=http://localhost:8080/...` (claim validation) + `JWK_SET_URI=http://keycloak:8080/.../certs` (key fetch from internal network)

### Port map (all containers)
| Service | Host port |
|---|---|
| Keycloak | 8080 |
| PostgreSQL | 5432 |
| Redpanda Kafka | 9092 |
| IBM MQ | 1414, 9443 |
| account-verifier HTTP | 8085 |
| account-verifier gRPC | 9090 |
| clearing-house HTTP | 8083 |
| payment-gateway API | 8090 |
| spa-mobile-app | 3000 |

---

## SPA Overhaul (2026-06-27 — third session)

### Problems fixed
- **Keycloak never initialized** — `main.js` didn't call `keycloak.init()` before mounting; login button was a no-op. Fixed: async `bootstrap()` in `main.js` awaits `init()` before `createApp().mount()`.
- **Router guard broken** — called `useKeycloak()` per-navigation instead of using the module-level shared ref. Fixed.
- **Wrong logo** — no Red Hat hat SVG anywhere. Fixed.

### What changed (SPA only)
| File | Change |
|---|---|
| `src/main.js` | Async bootstrap — init Keycloak before mount |
| `src/composables/useKeycloak.js` | PKCE S256, proper redirects on login/logout, error handling |
| `src/router/index.js` | Module-scope `isAuthenticated`, added `/dashboard` route |
| `src/App.vue` | Hide header/nav on `/login` route |
| `src/components/AppHeader.vue` | Red Hat hat SVG logo, username, dark toggle, logout |
| `src/components/BottomNav.vue` | Working routes (dashboard, payments); disabled (history, profile) |
| `src/views/LoginView.vue` | Full Red Hat branding — red bg, hat logo, pill sign-in button |
| `src/views/DashboardView.vue` | **NEW** — balance card, "Send Money" CTA |
| `src/views/PaymentsView.vue` | Restyled, source account is now `<select>` |
| `src/views/PaymentStatusView.vue` | Color-coded states: PENDING=amber, COMPLETED=green, FAILED=red |
| `src/assets/main.css` | Complete rewrite: Red Hat Display font, `#ee0000` palette, dark mode, CSS variables |
| `index.html` | Google Fonts — Red Hat Display (400/500/700) |
| `public/favicon.svg` | **NEW** — Red Hat hat SVG |

### Design system
- Primary: `#ee0000` (Red Hat Red)
- Font: Red Hat Display
- Layout: max-width 420px, mobile-first
- Dark mode: `body.dark` CSS class toggle
- Login page: full-red viewport, centered white card

### Tests
All 19 Vitest tests pass (updated `PaymentsView.spec.js` selector for `<select>` sourceAccount).
