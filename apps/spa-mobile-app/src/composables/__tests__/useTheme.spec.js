import { describe, it, expect, beforeEach, vi } from 'vitest'

describe('useTheme', () => {
  beforeEach(() => {
    document.body.classList.remove('dark')
    localStorage.clear()
    vi.resetModules()
  })

  it('toggleTheme adds dark class to body', async () => {
    const { useTheme } = await import('../useTheme.js')
    const { toggleTheme } = useTheme()
    toggleTheme()
    expect(document.body.classList.contains('dark')).toBe(true)
  })

  it('toggleTheme persists theme to localStorage', async () => {
    const { useTheme } = await import('../useTheme.js')
    const { toggleTheme } = useTheme()
    toggleTheme()
    expect(localStorage.getItem('theme')).toBe('dark')
  })

  it('toggleTheme reverts to light on second call', async () => {
    const { useTheme } = await import('../useTheme.js')
    const { toggleTheme, theme } = useTheme()
    toggleTheme()
    expect(theme.value).toBe('dark')
    toggleTheme()
    expect(theme.value).toBe('light')
    expect(document.body.classList.contains('dark')).toBe(false)
  })

  it('toggleTheme stores light in localStorage on second toggle', async () => {
    const { useTheme } = await import('../useTheme.js')
    const { toggleTheme } = useTheme()
    toggleTheme()
    toggleTheme()
    expect(localStorage.getItem('theme')).toBe('light')
  })
})
