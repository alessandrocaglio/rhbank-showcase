import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Mock the payments API
vi.mock('../../api/payments.js', () => ({
  getPaymentStreamUrl: (txId) => `http://localhost/api/v1/payments/stream/${txId}`,
}))

// Mock vue's onUnmounted so it doesn't error outside component context
vi.mock('vue', async () => {
  const actual = await vi.importActual('vue')
  return { ...actual, onUnmounted: vi.fn() }
})

class MockEventSource {
  constructor(url) {
    this.url = url
    this.onopen = null
    this.onmessage = null
    this.onerror = null
    MockEventSource.instances.push(this)
  }
  close() { this.closed = true }
  static instances = []
  static reset() { MockEventSource.instances = [] }
}

describe('usePaymentStream', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    MockEventSource.reset()
    vi.stubGlobal('EventSource', MockEventSource)
    vi.resetModules()  // CRITICAL: reset composable module state
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('opens EventSource with correct URL', async () => {
    const { usePaymentStream } = await import('../usePaymentStream.js')
    usePaymentStream('txn-001')
    expect(MockEventSource.instances[0].url).toBe('http://localhost/api/v1/payments/stream/txn-001')
  })

  it('updates status when message received', async () => {
    const { usePaymentStream } = await import('../usePaymentStream.js')
    const { status, detail } = usePaymentStream('txn-002')

    const es = MockEventSource.instances[0]
    es.onmessage({ data: JSON.stringify({ status: 'COMPLETED', detail: 'Done' }) })

    expect(status.value).toBe('COMPLETED')
    expect(detail.value).toBe('Done')
  })

  it('sets isConnected false on error and attempts reconnect', async () => {
    const { usePaymentStream } = await import('../usePaymentStream.js')
    const { isConnected } = usePaymentStream('txn-003')

    const es = MockEventSource.instances[0]
    es.onerror()

    expect(isConnected.value).toBe(false)

    // Advance timer — retry should create a new EventSource
    vi.advanceTimersByTime(2100)
    expect(MockEventSource.instances.length).toBe(2)
  })

  it('cleanup closes EventSource', async () => {
    const { usePaymentStream } = await import('../usePaymentStream.js')
    const { cleanup } = usePaymentStream('txn-004')
    const es = MockEventSource.instances[0]

    cleanup()

    expect(es.closed).toBe(true)
  })

  it('sets CONNECTION_LOST after max retries', async () => {
    const { usePaymentStream } = await import('../usePaymentStream.js')
    const { status } = usePaymentStream('txn-005')

    // Trigger 3 errors (MAX_RETRIES)
    for (let i = 0; i < 3; i++) {
      const es = MockEventSource.instances[MockEventSource.instances.length - 1]
      es.onerror()
      vi.advanceTimersByTime(2100)
    }
    // Trigger one more error on the last instance (the 3rd retry)
    const last = MockEventSource.instances[MockEventSource.instances.length - 1]
    last.onerror()

    expect(status.value).toBe('CONNECTION_LOST')
  })
})
