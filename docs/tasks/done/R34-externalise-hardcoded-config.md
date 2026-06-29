# R34 — Externalise Hard-Coded Config Values as Env Vars (prerequisite for R32)

## Goal
Three application-level config values are not reachable by Helm (env var injection) today.
Fix them so that `helm install showcase-apps --set ...` can override every connection string and
all deployment manifests work without touching source code.

This is a **prerequisite for R32** (Helm charts).

---

## Fix 1 — payment-gateway: add JWK_SET_URI to application.yml

**Why it matters:** In OpenShift, Keycloak is accessed on two different URLs —
- **External** (browser / JWT issuer claim): `https://keycloak.apps.<cluster>`
- **Internal** (JWK key fetch, pod-to-pod): `http://keycloak.<namespace>.svc.cluster.local:8080`

The `issuer-uri` alone makes Spring Boot try to fetch the JWKS from the issuer URL, which is
unreachable from inside the pod (external hostname, no cluster DNS). Splitting into
`issuer-uri` (claim validation) + `jwk-set-uri` (key download) fixes this.

**File:** `apps/payment-gateway/src/main/resources/application.yml`

Current:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:http://localhost:8080/realms/BankDemoRealm}
```

Change to:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:http://localhost:8080/realms/BankDemoRealm}
          jwk-set-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI:}
```

Leaving `jwk-set-uri` empty means Spring Boot falls back to auto-discovery from `issuer-uri` —
so the local dev flow (single Keycloak URL) is unaffected. In OpenShift, Helm sets it to the
internal service URL and the split takes effect.

No Java code changes needed for this fix.

---

## Fix 2 — payment-gateway: CORS allowed origins from env var

**Why it matters:** `SecurityConfig.java` hard-codes `List.of("http://localhost:3000")`. In
OpenShift the SPA runs on `https://spa-mobile-app.apps.<cluster>`. The CORS header mismatch
causes the browser to block all requests.

### 2a — application.yml

Add under `app:`:
```yaml
app:
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:3000}
  ...
```

### 2b — SecurityConfig.java

Inject the value and split on comma (supports multiple origins):

```java
@Value("${app.cors.allowed-origins:http://localhost:3000}")
private String corsAllowedOrigins;

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(
        Arrays.stream(corsAllowedOrigins.split(","))
              .map(String::trim)
              .toList()
    );
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

Add `import java.util.Arrays;` if not already present.

### 2c — Test

Add or update a test in `SecurityConfigTest` (or equivalent) to verify that:
- A single origin string is parsed correctly
- A comma-separated list is split into multiple allowed origins
- The bean is wired correctly with `@SpringBootTest` or a `@WebMvcTest` slice

Run: `./mvnw test -pl apps/payment-gateway -Dtest=SecurityConfigTest -q`

Then full suite: `./mvnw test -pl apps/payment-gateway -q`

---

## Fix 3 — clearing-house: simulation delay backed by env vars

**Why it matters:** `clearing.simulation.delay-ms.min` and `clearing.simulation.delay-ms.max` are
only overridden in the `%test` profile. The showcase.sh script passes them as `-D` JVM flags at
runtime. A Helm Deployment cannot inject `-D` flags into `JAVA_TOOL_OPTIONS` cleanly — env vars
are the right mechanism.

**File:** `apps/clearing-house/src/main/resources/application.properties`

Add the two properties in the main (non-test) section:
```properties
clearing.simulation.delay-ms.min=${CLEARING_SIMULATION_DELAY_MS_MIN:100}
clearing.simulation.delay-ms.max=${CLEARING_SIMULATION_DELAY_MS_MAX:500}
```

No Java code change needed — the existing `ClearingProperties` bean already reads these keys;
we are only adding the env-var backing with sensible defaults.

Verify test profile override still wins (the `%test.clearing.simulation.delay-ms.min=0` line
overrides the main-profile value in Quarkus — no change needed there).

Run: `./mvnw test -pl apps/clearing-house -q`

---

## Acceptance

- `./mvnw test -pl apps/payment-gateway -q` — all tests pass (including new CORS test)
- `./mvnw test -pl apps/clearing-house -q` — all tests pass
- Setting `APP_CORS_ALLOWED_ORIGINS=https://example.com` via env var overrides CORS at runtime
- Setting `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://internal/certs` overrides the JWK endpoint
- Setting `CLEARING_SIMULATION_DELAY_MS_MIN=0 CLEARING_SIMULATION_DELAY_MS_MAX=0` results in zero delay (manual smoke test)

## Blocks
- **R32** — Helm charts depend on these env var names being present in the app configs
