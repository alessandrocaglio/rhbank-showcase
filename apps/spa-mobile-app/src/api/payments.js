import { useKeycloak } from '../composables/useKeycloak.js'
import { config } from '../config.js'

export async function initiatePayment(payload) {
  const { getToken } = useKeycloak()
  const res = await fetch(`${config.apiBaseUrl}/api/v1/payments`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${getToken()}`,
    },
    body: JSON.stringify(payload),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: 'Request failed' }))
    throw new Error(err.message || `HTTP ${res.status}`)
  }
  return res.json()
}

export function getPaymentStreamUrl(txId) {
  return `${config.apiBaseUrl}/api/v1/payments/stream/${txId}`
}
