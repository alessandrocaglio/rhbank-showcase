# T03 · Docker Compose Local Stack + Keycloak Realm JSON

**Phase:** 1 — Local Infrastructure
**Status:** todo
**Depends on:** T01 (for directory structure)

## Deliverables
- `infra/docker/docker-compose.yml` — all 4 infra services + `redpanda-init` topic creation job
- Healthchecks for all services (Keycloak, PostgreSQL, Redpanda, IBM MQ)
- `infra/docker/postgres-init.sql` — creates `accounts_db`, `ledger_db`, `appuser`
- `infra/docker/config.mqsc` — MQSC script: channels, queues, dead-letter queue
- `infra/docker/payment-realm.json` — Keycloak realm export: `BankDemoRealm`, clients (`spa-payment-client`, `backend-gateway-service`), role `payment-init`, test user `testuser/password`

## Services & Ports (host-mapped)
| Service    | Host Port(s)                         |
|------------|--------------------------------------|
| keycloak   | 8080                                 |
| postgres   | 5432                                 |
| redpanda   | 9092 (Kafka), 8081 (Schema Registry) |
| ibmmq      | 1414 (MQ), 9443 (Console)            |

## Unit Tests
N/A — infrastructure config. Verification is operational.

## Verification
```bash
docker-compose -f infra/docker/docker-compose.yml up -d
docker-compose -f infra/docker/docker-compose.yml ps        # all services healthy
curl -sf http://localhost:8080/realms/BankDemoRealm/.well-known/openid-configuration | jq .issuer
psql postgresql://postgres:postgrespassword@localhost:5432/postgres -c "\l" | grep -E "accounts_db|ledger_db"
rpk topic list --brokers localhost:9092                     # shows 4 topics
docker-compose -f infra/docker/docker-compose.yml down -v
```

## Acceptance Criteria
- [ ] All 4 services reach healthy state within 120s
- [ ] Keycloak serves `BankDemoRealm` OIDC discovery endpoint
- [ ] PostgreSQL has `accounts_db` and `ledger_db`
- [ ] Redpanda has all 4 topics (`payment-approved`, `payment-completed`, `*.DLT`)
- [ ] IBM MQ queue `DEV.QUEUE.CLEARING` exists in QM1
- [ ] `docker-compose down -v` cleans up without errors
