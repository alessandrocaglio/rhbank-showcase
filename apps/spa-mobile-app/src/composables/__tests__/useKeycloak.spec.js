import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock keycloak-js BEFORE importing the composable
vi.mock('keycloak-js', () => {
  const mockKeycloak = {
    init: vi.fn().mockResolvedValue(true),
    login: vi.fn(),
    logout: vi.fn(),
    updateToken: vi.fn().mockResolvedValue(true),
    token: 'mock-token-123',
    tokenParsed: { preferred_username: 'testuser' },
    onTokenExpired: null,
  }
  return { default: vi.fn(() => mockKeycloak) }
})

// Mock config.js to avoid import.meta.env issues in test environment
vi.mock('../config.js', () => ({
  config: {
    keycloakUrl: 'http://localhost:8080',
    keycloakRealm: 'TestRealm',
    keycloakClientId: 'test-client',
    apiBaseUrl: 'http://localhost:8090',
  },
}))

describe('useKeycloak', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('calls init with check-sso onLoad option', async () => {
    const { default: Keycloak } = await import('keycloak-js')
    const { useKeycloak } = await import('../useKeycloak.js')
    const { init } = useKeycloak()
    await init()
    const instance = Keycloak.mock.results[0]?.value
    expect(instance.init).toHaveBeenCalledWith(
      expect.objectContaining({ onLoad: 'check-sso' })
    )
  })

  it('calls init with checkLoginIframe: false', async () => {
    const { default: Keycloak } = await import('keycloak-js')
    const { useKeycloak } = await import('../useKeycloak.js')
    const { init } = useKeycloak()
    await init()
    const instance = Keycloak.mock.results[0]?.value
    expect(instance.init).toHaveBeenCalledWith(
      expect.objectContaining({ checkLoginIframe: false })
    )
  })

  it('getToken returns the keycloak token', async () => {
    const { useKeycloak } = await import('../useKeycloak.js')
    const { getToken } = useKeycloak()
    expect(getToken()).toBe('mock-token-123')
  })

  it('isAuthenticated is true after successful init', async () => {
    const { useKeycloak } = await import('../useKeycloak.js')
    const { init, isAuthenticated } = useKeycloak()
    await init()
    expect(isAuthenticated.value).toBe(true)
  })

  it('sets username from tokenParsed after successful init', async () => {
    const { useKeycloak } = await import('../useKeycloak.js')
    const { init, username } = useKeycloak()
    await init()
    expect(username.value).toBe('testuser')
  })

  it('refreshToken resolves without calling login when updateToken succeeds', async () => {
    const { default: Keycloak } = await import('keycloak-js')
    const { useKeycloak } = await import('../useKeycloak.js')
    const { refreshToken } = useKeycloak()
    const instance = Keycloak.mock.results[0]?.value
    instance.updateToken.mockResolvedValue(true)
    await refreshToken(30)
    expect(instance.updateToken).toHaveBeenCalledWith(30)
    expect(instance.login).not.toHaveBeenCalled()
  })

  it('refreshToken calls login when updateToken rejects', async () => {
    const { default: Keycloak } = await import('keycloak-js')
    const { useKeycloak } = await import('../useKeycloak.js')
    const { refreshToken } = useKeycloak()
    const instance = Keycloak.mock.results[0]?.value
    instance.updateToken.mockRejectedValue(new Error('Session expired'))
    await refreshToken(30)
    expect(instance.login).toHaveBeenCalled()
  })
})
