<template>
  <div class="status-wrapper">
    <div class="card status-card">

      <!-- Transaction ID -->
      <div class="tx-header">
        <span class="tx-label">Transaction ID</span>
        <span class="tx-id">{{ txId }}</span>
      </div>

      <!-- Status icon + title -->
      <div class="status-center">
        <!-- PENDING -->
        <div v-if="status === 'PENDING'" class="status-icon-wrap pending">
          <span class="spinner"></span>
        </div>
        <!-- COMPLETED -->
        <div v-else-if="status === 'COMPLETED'" class="status-icon-wrap completed">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="status-icon">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </div>
        <!-- FAILED -->
        <div v-else-if="status === 'FAILED'" class="status-icon-wrap failed">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" class="status-icon">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </div>
        <!-- Unknown / initial -->
        <div v-else class="status-icon-wrap pending">
          <span class="spinner"></span>
        </div>
      </div>

      <!-- Status title -->
      <p v-if="status === 'COMPLETED'" class="status-title completed-text">Payment Completed!</p>
      <p v-else-if="status === 'FAILED'" class="status-title failed-text">Payment Failed</p>
      <p v-else class="status-title pending-text">Processing Payment…</p>

      <!-- Detail message -->
      <p class="detail-text">{{ detail }}</p>

      <!-- Live connection indicator -->
      <div class="connection-row">
        <span :class="['dot', isConnected ? 'dot-green' : 'dot-grey']"></span>
        <span class="connection-label">{{ isConnected ? 'Live' : 'Reconnecting…' }}</span>
      </div>

      <!-- CTA button -->
      <button class="btn-primary" @click="$router.push('/payments')">
        Send Another Payment
      </button>
    </div>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { usePaymentStream } from '../composables/usePaymentStream.js'

const route = useRoute()
const txId = route.params.txId

const { status, detail, isConnected } = usePaymentStream(txId)
</script>

<style scoped>
.status-wrapper {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 1rem;
}

.status-card {
  max-width: 400px;
  margin: 0 auto;
  width: 100%;
  text-align: center;
}

/* Transaction ID */
.tx-header {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 1.5rem;
  text-align: left;
}

.tx-label {
  font-size: 0.75rem;
  color: var(--text-color-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.tx-id {
  font-family: monospace;
  font-size: 0.85rem;
  color: var(--text-color-secondary);
  word-break: break-all;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Status icon area */
.status-center {
  display: flex;
  justify-content: center;
  margin: 1.5rem 0 1rem;
}

.status-icon-wrap {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.status-icon-wrap.pending  { background-color: rgba(245, 158, 11, 0.12); }
.status-icon-wrap.completed { background-color: rgba(34, 197, 94, 0.12); }
.status-icon-wrap.failed   { background-color: rgba(238, 0, 0, 0.10); }

.status-icon {
  width: 36px;
  height: 36px;
}

.status-icon-wrap.completed .status-icon { color: #22c55e; }
.status-icon-wrap.failed    .status-icon { color: #ee0000; }

/* Spinner for pending */
.spinner {
  display: inline-block;
  width: 32px;
  height: 32px;
  border: 3px solid rgba(245, 158, 11, 0.3);
  border-top-color: #f59e0b;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Status title */
.status-title {
  font-size: 1.2rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.pending-text   { color: #f59e0b; }
.completed-text { color: #22c55e; }
.failed-text    { color: #ee0000; }

/* Detail text */
.detail-text {
  text-align: center;
  color: var(--text-color-secondary);
  font-size: 0.9rem;
  margin-bottom: 1.5rem;
}

/* Connection row */
.connection-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  justify-content: center;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-green { background: #22c55e; }
.dot-grey  { background: #9ca3af; }

.connection-label {
  font-size: 0.8rem;
  color: var(--text-color-secondary);
}
</style>
