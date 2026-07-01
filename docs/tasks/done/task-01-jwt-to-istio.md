# Task 01 — Move JWT Validation to Istio Service Mesh

## Goal

Remove all JWT token validation logic from application code. JWT authentication and authorization will be enforced by Istio at the mesh layer via `RequestAuthentication` and `AuthorizationPolicy` resources (created in a separate stage). The SPA continues to authenticate with Keycloak and send `Authorization: Bearer <token>` headers — this behavior is unchanged. In local docker-compose dev, the token is still sent but not verified — this is acceptable since local is development-only.

---

## Decisions

| Question | Decision |
|---|---|
| CORS | Stays in the application as a minimal `WebMvcConfigurer` bean — no Istio dependency |
| Local dev | All endpoints open without JWT check — acceptable for development |
| JWT claims in app code | No business logic reads claims; no claim forwarding needed |

---

## Scope

Only `payment-gateway` performs JWT validation. The other three services are already unauthenticated at the application level and require no changes.

---

## Files to Change

### 1. Delete `SecurityConfig.java`

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java`

**Action:** Delete entirely. This class configures JWT decoding, role-based authorization, stateless session, CORS, and the development bypass filter. All of that is removed.

---

### 2. Create a minimal CORS config to replace the CORS portion of SecurityConfig

**File:** `apps/payment-gateway/src/main/java/com/showcase/gateway/config/WebConfig.java` *(new file)*

**Action:** Create a plain `WebMvcConfigurer` that replicas the CORS rules from `SecurityConfig`. No Spring Security import. Example shape:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

Match the origin pattern and allowed methods to whatever `SecurityConfig.java` currently defines.

---

### 3. Remove `spring-boot-starter-oauth2-resource-server` dependency

**File:** `apps/payment-gateway/pom.xml` line ~37

```xml
<!-- REMOVE -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

`spring-security-test` (test scope, line ~82) was only needed to mock JWT in tests. Remove it too:

```xml
<!-- REMOVE -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 4. Remove JWT config from `application.yml`

**File:** `apps/payment-gateway/src/main/resources/application.yml`

Remove the `spring.security.oauth2.resourceserver.jwt` block (lines ~4-9):
```yaml
# REMOVE
security:
  oauth2:
    resourceserver:
      jwt:
        issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:...}
        jwk-set-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI:}
```

Remove the JWT bypass property (lines ~37-39):
```yaml
# REMOVE
auth:
  jwt:
    bypass: ${AUTH_JWT_BYPASS:false}
```

---

### 5. Remove JWT env vars from K8s deployment

**File:** `infra/k8s/apps/payment-gateway/deployment.yaml` lines ~39-40

```yaml
# REMOVE
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
  value: "http://keycloak:8080/realms/BankDemoRealm"
```

Also remove any `AUTH_JWT_BYPASS` env var if present.

---

### 6. Remove JWT env vars from docker-compose

**File:** `infra/docker/docker-compose.yml` lines ~250-251

```yaml
# REMOVE from payment-gateway service environment
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://localhost:8080/realms/BankDemoRealm
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/BankDemoRealm/protocol/openid-connect/certs
```

---

## What Istio Resources Will Replace This

*(Created in a separate stage — not in scope of this task)*

```yaml
# RequestAuthentication — Istio validates JWT signature and issuer
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: payment-gateway-jwt
  namespace: <app-namespace>
spec:
  selector:
    matchLabels:
      app: payment-gateway
  jwtRules:
    - issuer: "https://<keycloak-host>/realms/BankDemoRealm"
      jwksUri: "https://<keycloak-host>/realms/BankDemoRealm/protocol/openid-connect/certs"
      forwardOriginalToken: true

# AuthorizationPolicy — deny requests without a valid principal
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: payment-gateway-require-jwt
  namespace: <app-namespace>
spec:
  selector:
    matchLabels:
      app: payment-gateway
  action: ALLOW
  rules:
    - from:
        - source:
            requestPrincipals: ["*"]
      to:
        - operation:
            methods: ["POST"]
            paths: ["/api/v1/payments"]
```

---

## Acceptance Criteria

- [ ] `SecurityConfig.java` deleted
- [ ] `WebConfig.java` created with CORS rules matching the original config
- [ ] `spring-boot-starter-oauth2-resource-server` removed from `payment-gateway/pom.xml`
- [ ] `spring-security-test` removed from `payment-gateway/pom.xml`
- [ ] `spring.security.oauth2.resourceserver.jwt.*` block removed from `application.yml`
- [ ] `auth.jwt.bypass` property removed from `application.yml`
- [ ] `AUTH_JWT_BYPASS` env var and any bypass filter class deleted
- [ ] JWT env vars removed from `infra/k8s/apps/payment-gateway/deployment.yaml`
- [ ] JWT env vars removed from `infra/docker/docker-compose.yml`
- [ ] `./mvnw clean package -pl apps/payment-gateway -DskipTests` succeeds
- [ ] No `spring-security` or `oauth2` imports remain in the payment-gateway source tree
