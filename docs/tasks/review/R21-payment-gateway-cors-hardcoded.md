# R21 — 🟠 payment-gateway: CORS allowed origin hardcoded to `localhost:3000`

## Problem
`SecurityConfig.java:47`:
```java
config.setAllowedOrigins(List.of("http://localhost:3000"));
```
On OpenShift, the SPA is served from a different origin (the Route URL). All cross-origin
requests from the browser to `payment-gateway` will be blocked by the browser's CORS policy,
silently preventing any payments from being submitted.

## Files to change
- `apps/payment-gateway/src/main/java/com/showcase/gateway/config/SecurityConfig.java`
- `apps/payment-gateway/src/main/resources/application.yml`

## Fix
Read the allowed origin(s) from configuration:

```yaml
# application.yml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

```java
@Value("${app.cors.allowed-origins}")
private String[] allowedOrigins;

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(Arrays.asList(allowedOrigins));
    ...
}
```

In the K8s deployment, set:
```yaml
- name: CORS_ALLOWED_ORIGINS
  value: "https://spa-showcase.apps.<cluster-domain>"
```

## Acceptance
- Payment form on the containerised SPA can POST to payment-gateway without CORS errors
- `SecurityConfigTest` updated to verify the configurable origin
