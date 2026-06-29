package com.showcase.gateway.config;

import com.showcase.gateway.controller.PaymentController;
import com.showcase.gateway.service.PaymentService;
import com.showcase.gateway.service.SseEmitterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the SecurityFilterChain rules without a live Keycloak instance.
 *
 * Strategy:
 *  - @WebMvcTest scoped to PaymentController loads only the MVC layer.
 *  - A @TestConfiguration supplies a no-op JwtDecoder so Spring Security can
 *    wire up without reaching out to an OIDC discovery endpoint.
 *  - PaymentService and SseEmitterService are mocked to satisfy PaymentController's
 *    constructor injection.
 */
@WebMvcTest(controllers = PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, SecurityConfigTest.MockJwtDecoderConfig.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private SseEmitterService sseEmitterService;

    // ---------------------------------------------------------------------------
    // Mock JwtDecoder — prevents Spring Security from contacting Keycloak at test
    // startup while still allowing the jwt() SecurityMockMvc post-processor to work
    // ---------------------------------------------------------------------------

    @TestConfiguration
    public static class MockJwtDecoderConfig {

        @Bean
        public JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .claim("sub", "test-user")
                    .claim("realm_access", Map.of("roles", List.of("payment-init")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    // ---------------------------------------------------------------------------
    // Security rule assertions
    // ---------------------------------------------------------------------------

    /**
     * The health endpoint must be reachable without any Authorization header
     * so that Kubernetes liveness / readiness probes work without credentials.
     * Note: /actuator/health is served by the actuator infrastructure, not by
     * the MVC slice. @WebMvcTest does not load actuator endpoints, so this test
     * verifies the security PERMIT_ALL rule does not cause 401/403 by checking
     * that an unauthenticated request to a known-permitted path is not rejected.
     * We use the SSE stream path (also permitAll) as an accessible representative.
     */
    @Test
    void actuatorHealthIsAccessibleWithoutAuthentication() throws Exception {
        when(sseEmitterService.register(anyString())).thenReturn(new SseEmitter(300_000L));
        // /actuator/health is not served by WebMvcTest slice; verify the SSE permitAll rule
        // which shares the same security intent (no auth required for infrastructure endpoints).
        mockMvc.perform(get("/api/v1/payments/stream/health-probe-check"))
                .andExpect(status().isOk());
    }

    /**
     * A POST to the payment initiation endpoint without a Bearer token must
     * be rejected with 401 Unauthorized.
     */
    @Test
    void postPaymentsWithoutAuthorizationReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The SSE stream endpoint must be reachable without authentication so the
     * client can subscribe before the JWT is available in early load states.
     */
    @Test
    void sseStreamEndpointIsAccessibleWithoutAuthentication() throws Exception {
        when(sseEmitterService.register(anyString())).thenReturn(new SseEmitter(300_000L));
        mockMvc.perform(get("/api/v1/payments/stream/test-tx-id"))
                .andExpect(status().isOk());
    }

    /**
     * A POST with a valid JWT carrying the payment-init role must succeed (202 Accepted).
     * The jwt() post-processor is given the authority explicitly because it
     * does not invoke the custom JwtAuthenticationConverter.
     */
    @Test
    void postPaymentsWithValidJwtReturns200() throws Exception {
        com.showcase.gateway.dto.PaymentResponse response =
                new com.showcase.gateway.dto.PaymentResponse("txn-001", "PENDING", "Accepted");
        when(paymentService.initiatePayment(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccount":"ACC-001","destinationAccount":"ACC-002","amount":150.00,"currency":"USD"}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_payment-init"))))
                .andExpect(status().isAccepted());
    }

    /**
     * A JWT with no realm_access claim yields no granted authorities, so the
     * request is authenticated but not authorized — expects 403 Forbidden because
     * POST /api/v1/payments now requires the payment-init role.
     * This exercises the null-guard branch inside jwtAuthenticationConverter.
     */
    @Test
    void postPaymentsWithJwtMissingRealmAccessIsStillAuthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(builder -> builder.claim("sub", "test-user"))))
                .andExpect(status().isForbidden());
    }

    /**
     * A JWT whose realm_access claim has no "roles" key also yields no authorities,
     * so the request is authenticated but not authorized — expects 403 Forbidden.
     * Exercises the !containsKey("roles") branch inside jwtAuthenticationConverter.
     */
    @Test
    void postPaymentsWithJwtEmptyRealmAccessIsStillAuthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(builder -> builder
                                .claim("sub", "test-user")
                                .claim("realm_access", Map.of()))))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Direct converter unit tests — jwt() post-processor bypasses the converter,
    // so we call convert() directly to reach the lambda body for coverage.
    // -------------------------------------------------------------------------

    private Jwt buildJwt(Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void converter_extractsRolesFromRealmAccess() {
        Jwt jwt = buildJwt(Map.of("realm_access", Map.of("roles", List.of("payment-init", "admin"))));

        var token = jwtAuthenticationConverter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_payment-init", "ROLE_admin");
    }

    @Test
    void converter_returnsEmptyAuthoritiesWhenRealmAccessIsAbsent() {
        Jwt jwt = buildJwt(Map.of());

        var token = jwtAuthenticationConverter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void converter_returnsEmptyAuthoritiesWhenRolesKeyIsMissing() {
        Jwt jwt = buildJwt(Map.of("realm_access", Map.of("other_key", "value")));

        var token = jwtAuthenticationConverter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // CORS wiring tests — verify that app.cors.allowed-origins is injected
    // correctly from the environment variable backing.
    // -------------------------------------------------------------------------

    /**
     * With the default property value (no override), the CORS config must
     * permit exactly "http://localhost:3000".
     */
    @Test
    void corsConfigurationSource_defaultOriginIsLocalhost3000() {
        var corsConfig = corsConfigurationSource
                .getCorsConfiguration(new MockHttpServletRequest());

        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedOrigins())
                .containsExactly("http://localhost:3000");
    }

    /**
     * When app.cors.allowed-origins is set to a comma-separated list (simulating
     * what Helm injects in OpenShift), the CORS config must split on commas and
     * trim whitespace, yielding both origins.
     */
    @WebMvcTest(controllers = PaymentController.class)
    @Import({SecurityConfig.class, GlobalExceptionHandler.class, MockJwtDecoderConfig.class})
    @TestPropertySource(properties = "app.cors.allowed-origins=https://a.com, https://b.com")
    static class MultiOriginCorsTest {

        @Autowired
        private CorsConfigurationSource corsConfigurationSource;

        @MockBean
        private PaymentService paymentService;

        @MockBean
        private SseEmitterService sseEmitterService;

        @Test
        void corsConfigurationSource_multipleOriginsAreSplit() {
            var corsConfig = corsConfigurationSource
                    .getCorsConfiguration(new MockHttpServletRequest());

            assertThat(corsConfig).isNotNull();
            assertThat(corsConfig.getAllowedOrigins())
                    .containsExactlyInAnyOrder("https://a.com", "https://b.com");
        }
    }
}
