import { ref, onUnmounted } from 'vue'
import { getPaymentStreamUrl } from '../api/payments.js'

export function usePaymentStream(txId) {
  const status = ref('PENDING')
  const detail = ref('Waiting for processing...')
  const isConnected = ref(false)

  let eventSource = null
  let retryCount = 0
  const MAX_RETRIES = 3
  const RETRY_DELAY = 2000

  function connect() {
    const url = getPaymentStreamUrl(txId)
    eventSource = new EventSource(url)

    eventSource.onopen = () => {
      isConnected.value = true
      retryCount = 0
    }

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.status) status.value = data.status
        if (data.detail) detail.value = data.detail
      } catch {
        // malformed message — ignore
      }
    }

    eventSource.onerror = () => {
      isConnected.value = false
      eventSource.close()
      eventSource = null

      if (retryCount < MAX_RETRIES) {
        retryCount++
        setTimeout(connect, RETRY_DELAY)
      } else {
        status.value = 'CONNECTION_LOST'
        detail.value = 'Unable to connect. Please refresh the page.'
      }
    }
  }

  function cleanup() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  connect()
  onUnmounted(cleanup)

  return { status, detail, isConnected, cleanup }
}
