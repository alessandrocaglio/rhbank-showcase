import { describe, it, expect, vi, beforeEach } from 'vitest'

// Make useRouter a vi.fn() so individual tests can override its return value
const mockPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: vi.fn(() => ({ push: mockPush })),
}))

// Mock payments API with a vi.fn() so tests can configure resolve/reject per case
vi.mock('../../api/payments.js', () => ({
  initiatePayment: vi.fn(),
}))

describe('usePayment', () => {
  beforeEach(async () => {
    vi.resetModules()
    mockPush.mockReset()
    // Reset the initiatePayment mock state between tests
    const { initiatePayment } = await import('../../api/payments.js')
    initiatePayment.mockReset()
  })

  it('submit with valid form calls initiatePayment with correct payload', async () => {
    const { initiatePayment } = await import('../../api/payments.js')
    initiatePayment.mockResolvedValue({ transactionId: 'txn-001' })

    const { usePayment } = await import('../usePayment.js')
    const { form, submit } = usePayment()

    form.sourceAccount = 'ACC-001'
    form.destinationAccount = 'ACC-002'
    form.amount = '150.00'
    form.currency = 'USD'

    await submit()

    expect(initiatePayment).toHaveBeenCalledWith({
      sourceAccount: 'ACC-001',
      destinationAccount: 'ACC-002',
      amount: 150.00,
      currency: 'USD',
    })
  })

  it('submit with empty sourceAccount sets error and does not call API', async () => {
    const { initiatePayment } = await import('../../api/payments.js')
    const { usePayment } = await import('../usePayment.js')
    const { form, errors, submit } = usePayment()

    form.sourceAccount = ''
    form.destinationAccount = 'ACC-002'
    form.amount = '100'

    await submit()

    expect(errors.sourceAccount).toBeTruthy()
    expect(initiatePayment).not.toHaveBeenCalled()
  })

  it('submit with negative amount sets amount error', async () => {
    const { usePayment } = await import('../usePayment.js')
    const { form, errors, submit } = usePayment()

    form.sourceAccount = 'ACC-001'
    form.destinationAccount = 'ACC-002'
    form.amount = '-5'

    await submit()

    expect(errors.amount).toBeTruthy()
  })

  it('API rejection sets errorMessage and does not navigate', async () => {
    const { initiatePayment } = await import('../../api/payments.js')
    initiatePayment.mockRejectedValue(new Error('Insufficient balance'))

    const { usePayment } = await import('../usePayment.js')
    const { form, errorMessage, submit } = usePayment()

    form.sourceAccount = 'ACC-001'
    form.destinationAccount = 'ACC-002'
    form.amount = '100'

    await submit()

    expect(errorMessage.value).toContain('Insufficient balance')
    expect(mockPush).not.toHaveBeenCalled()
  })
})
