import Keycloak from 'keycloak-js'
import { ref } from 'vue'
import { config } from '../config.js'

const keycloak = new Keycloak({
  url: config.keycloakUrl,
  realm: config.keycloakRealm,
  clientId: config.keycloakClientId,
})

const isAuthenticated = ref(false)
const username = ref('')

export function useKeycloak() {
  async function init() {
    const authenticated = await keycloak.init({
      onLoad: 'check-sso',
      checkLoginIframe: false,
    })
    isAuthenticated.value = authenticated
    if (authenticated) {
      username.value = keycloak.tokenParsed?.preferred_username || ''
    }
    keycloak.onTokenExpired = () =>
      keycloak.updateToken(30).catch(() => keycloak.logout())
    return authenticated
  }

  function login() {
    return keycloak.login()
  }

  function logout() {
    return keycloak.logout()
  }

  function getToken() {
    return keycloak.token
  }

  return { init, login, logout, getToken, isAuthenticated, username }
}
