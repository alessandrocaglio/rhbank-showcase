# T10 · payment-gateway: SSE Emitter Service

**Phase:** 3 — payment-gateway (Spring Boot 3.3.x)
**Status:** todo
**Depends on:** T09

## Deliverables
- `PaymentStatusEvent` record — `transactionId`, `status` (enum: PENDING/COMPLETED/FAILED), `timestamp`, `detail`
- `SseEmitterServiceImpl` (implements `SseEmitterService`, `@Service`):
  - Internal store: `ConcurrentHashMap<String, SseEmitter>`
  - `register(txId)` — creates `SseEmitter(300_000L)`, registers completion + timeout callbacks that call `remove(txId)`, adds to map, returns emitter
  - `resolve(txId, event)` — looks up emitter, sends event as JSON, calls `complete()`, removes from map
  - `resolve()` on unknown `txId` — logs warning, no-op (no exception)

## Unit Tests
`SseEmitterServiceTest` (`@ExtendWith(MockitoExtension.class)`):
- `register()` returns a non-null `SseEmitter`
- `register()` stores emitter in internal map (verify via second `resolve()` call succeeding)
- `resolve()` on known txId — event sent, emitter removed from map
- `resolve()` on unknown txId — no exception thrown
- Timeout callback (simulate via reflection or constructor-injected `Supplier<SseEmitter>`) removes emitter from map

## Verification
```bash
./mvnw test -pl apps/payment-gateway
# SseEmitterServiceTest — all 5 cases pass
# JaCoCo line coverage ≥ 80%
```

## Acceptance Criteria
- [ ] All 5 `SseEmitterServiceTest` cases pass
- [ ] `ConcurrentHashMap` used (thread-safe)
- [ ] Timeout callback self-evicts correctly
- [ ] JaCoCo line coverage ≥ 80%
