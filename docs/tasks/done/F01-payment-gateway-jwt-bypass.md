# F01 — payment-gateway: configurable JWT verification bypass

## Problem
Running the smoke test (`showcase.sh smoke`) against a local stack or a freshly-deployed
OpenShift cluster requires a live Keycloak issuing valid tokens. During CI smoke runs or
quick local iteration there is no OIDC provider available, so every POST to
`/api/v1/payments` returns `401 Unauthorized` and the pipeline cannot be exercised at all.

## Goal
Add an environment variable `AUTH_JWT_BYPASS` that, when set to `true`, disables JWT
signature and claims validation in `payment-gateway` and grants every unauthenticated
request the `payment-init` role automatically — letting the full trace pipeline run without
Keycloak.

> **Security:** The flag must default to `false`. It must never appear in any production
> Helm values file or OpenShift manifest. It is purely a dev/CI escape hatch.

---

## Files to change

| File | What changes |
|---|---|
| `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java` | Inject `@Value("${auth.jwt.bypass:false}")` and branch the filter chain |
| `apps/payment-gateway/src/main/resources/application.yml` | Add `auth.jwt.bypass: ${AUTH_JWT_BYPASS:false}` |
| `infra/docker/docker-compose.yml` | Add `AUTH_JWT_BYPASS: "true"` to the `payment-gateway` service env |
| `apps/payment-gateway/src/test/java/com/showcase/gateway/config/SecurityConfigTest.java` | Add a test verifying bypass mode permits unauthenticated POST and injects the role |

---

## Implementation detail

### `SecurityConfig.java`

```java
@Value("${auth.jwt.bypass:false}")
private boolean jwtBypass;
```

When `jwtBypass` is `true`, replace the filter chain's authorization and resource-server
blocks with `permitAll` and register a no-op `JwtDecoder` + a `Filter` that synthesises a
`UsernamePasswordAuthenticationToken` carrying `ROLE_payment-init`:

```java
if (jwtBypass) {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(bypassFilter(), UsernamePasswordAuthenticationFilter.class);
} else {
    // existing OAuth2 resource server chain
}
return http.build();
```

The bypass filter:

```java
private OncePerRequestFilter bypassFilter() {
    return new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(HttpServletRequest req,
                                        HttpServletResponse res,
                                        FilterChain chain) throws ... {
            var auth = new UsernamePasswordAuthenticationToken(
                "bypass-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_payment-init")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        }
    };
}
```

### `application.yml` addition

```yaml
auth:
  jwt:
    bypass: ${AUTH_JWT_BYPASS:false}
```

### `docker-compose.yml` (payment-gateway env block)

```yaml
AUTH_JWT_BYPASS: "true"
```

### New test case (`SecurityConfigTest`)

- Load context with `auth.jwt.bypass=true` (override via `@TestPropertySource`)
- POST `/api/v1/payments` with no `Authorization` header
- Assert HTTP 202 (not 401)
- Assert `SecurityContextHolder` holds `ROLE_payment-init`

---

## Acceptance

- `AUTH_JWT_BYPASS=false` (default) — POST without token → `401 Unauthorized` (existing behaviour unchanged)
- `AUTH_JWT_BYPASS=true` — POST without token → `202 Accepted` (pipeline proceeds)
- `showcase.sh smoke` passes end-to-end with `AUTH_JWT_BYPASS=true` in docker-compose
- No `AUTH_JWT_BYPASS` appears in any file under `infra/k8s/` or `infra/helm/`
- New test passes; existing JWT tests continue to pass
