const appConfig = window.__APP_CONFIG__ || {}

export const config = {
  keycloakUrl: appConfig.keycloakUrl || import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8080',
  keycloakRealm: appConfig.keycloakRealm || import.meta.env.VITE_KEYCLOAK_REALM || 'BankDemoRealm',
  keycloakClientId: appConfig.keycloakClientId || import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'spa-payment-client',
  apiBaseUrl: appConfig.apiBaseUrl || import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090',
}
