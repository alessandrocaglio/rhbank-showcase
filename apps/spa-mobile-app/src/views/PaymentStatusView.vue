<template>
  <div class="status-wrapper">
    <div class="card status-card">

      <div class="tx-header">
        <span class="tx-label">Transaction ID</span>
        <span class="tx-id">{{ txId }}</span>
      </div>

      <div class="status-center">
        <span :class="['status-badge', statusClass]">{{ status }}</span>
      </div>

      <p class="detail-text">{{ detail }}</p>

      <div class="connection-row">
        <span :class="['dot', isConnected ? 'dot-green' : 'dot-grey']"></span>
        <span class="connection-label">{{ isConnected ? 'Live' : 'Reconnecting…' }}</span>
      </div>

      <router-link to="/payments" class="back-link">← Back to Payments</router-link>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { usePaymentStream } from '../composables/usePaymentStream.js'

const route = useRoute()
const txId = route.params.txId

const { status, detail, isConnected } = usePaymentStream(txId)

const statusClass = computed(() => {
  switch (status.value) {
    case 'COMPLETED': return 'badge-green'
    case 'FAILED':    return 'badge-red'
    default:          return 'badge-amber'
  }
})
</script>

<style scoped>
.status-wrapper {
  padding: 1.5rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.status-card { max-width: 400px; margin: 0 auto; width: 100%; }

.tx-header { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 1.5rem; }
.tx-label { font-size: 0.75rem; color: var(--color-text-secondary); text-transform: uppercase; letter-spacing: 0.05em; }
.tx-id { font-family: monospace; font-size: 0.85rem; color: var(--color-text-secondary); word-break: break-all; }

.status-center { display: flex; justify-content: center; margin: 1.5rem 0; }

.status-badge {
  display: inline-block;
  padding: 0.4rem 1.2rem;
  border-radius: 999px;
  font-weight: 700;
  font-size: 0.9rem;
  color: #fff;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.badge-green  { background: #22c55e; }
.badge-red    { background: #ee0000; }
.badge-amber  { background: #f59e0b; }

.detail-text { text-align: center; color: var(--color-text-secondary); margin-bottom: 1.5rem; }

.connection-row { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 1.5rem; justify-content: center; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot-green { background: #22c55e; }
.dot-grey  { background: #9ca3af; }
.connection-label { font-size: 0.8rem; color: var(--color-text-secondary); }

.back-link { display: block; text-align: center; color: var(--color-primary); text-decoration: none; font-size: 0.9rem; }
.back-link:hover { text-decoration: underline; }
</style>
