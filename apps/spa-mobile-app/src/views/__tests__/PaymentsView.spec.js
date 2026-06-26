import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('../../api/payments.js', () => ({
  initiatePayment: vi.fn().mockResolvedValue({ transactionId: 'txn-abc' }),
}))

vi.mock('../../composables/useKeycloak.js', () => ({
  useKeycloak: () => ({ getToken: () => 'mock-token', isAuthenticated: { value: true } }),
}))

import PaymentsView from '../PaymentsView.vue'

describe('PaymentsView', () => {
  it('clicking submit with filled form calls initiatePayment', async () => {
    const { initiatePayment } = await import('../../api/payments.js')

    const wrapper = mount(PaymentsView, {
      global: { stubs: { BottomNav: true, AppHeader: true } }
    })

    await wrapper.find('input[placeholder="e.g. ACC-001"]').setValue('ACC-001')
    await wrapper.find('input[placeholder="e.g. ACC-002"]').setValue('ACC-002')
    await wrapper.find('input[type="number"]').setValue('150')
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()

    expect(initiatePayment).toHaveBeenCalledWith(
      expect.objectContaining({ sourceAccount: 'ACC-001', amount: 150 })
    )
  })
})
