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
    try {
      const authenticated = await keycloak.init({
        onLoad: 'check-sso',
        checkLoginIframe: false,
        pkceMethod: 'S256',
      })
      isAuthenticated.value = authenticated
      if (authenticated) {
        username.value = keycloak.tokenParsed?.preferred_username || ''
      }
      keycloak.onTokenExpired = () =>
        keycloak.updateToken(30).catch(() => {
          isAuthenticated.value = false
          keycloak.logout({ redirectUri: window.location.origin + '/login' })
        })
    } catch (e) {
      console.error('Keycloak init failed', e)
    }
  }

  function login() {
    return keycloak.login({ redirectUri: window.location.origin + '/dashboard' })
  }

  function logout() {
    isAuthenticated.value = false
    return keycloak.logout({ redirectUri: window.location.origin + '/login' })
  }

  function getToken() {
    return keycloak.token
  }

  async function refreshToken(minValiditySeconds = 30) {
    try {
      await keycloak.updateToken(minValiditySeconds)
    } catch (_) {
      // refresh failed — session expired; redirect to login
      keycloak.login()
    }
  }

  return { init, login, logout, getToken, isAuthenticated, username, refreshToken }
}
