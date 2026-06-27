<template>
  <div class="dashboard">
    <!-- Balance card -->
    <div class="balance-card">
      <p class="balance-label">Available Balance</p>
      <h2 class="balance-amount">$5,000.00</h2>
      <p class="account-number">ACC-001 &middot; USD</p>
    </div>

    <!-- Quick actions -->
    <div class="section quick-actions">
      <h3 class="section-title">Quick Actions</h3>
      <button class="action-btn" @click="router.push('/payments')">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <line x1="22" y1="2" x2="11" y2="13"/>
          <polygon points="22 2 15 22 11 13 2 9 22 2"/>
        </svg>
        Send Money
      </button>
    </div>

    <!-- Recent activity -->
    <div class="section recent-section">
      <h3 class="section-title">Recent Activity</h3>
      <div class="empty-state">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="40"
          height="40"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          class="empty-icon"
          aria-hidden="true"
        >
          <rect x="1" y="4" width="22" height="16" rx="2" ry="2"/>
          <line x1="1" y1="10" x2="23" y2="10"/>
        </svg>
        <p class="empty-text">No recent transactions</p>
        <p class="empty-hint">Submit a payment to see it here</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useKeycloak } from '../composables/useKeycloak.js'

const router = useRouter()
const { username } = useKeycloak()
</script>

<style scoped>
.dashboard {
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

/* Balance card */
.balance-card {
  background: #ee0000;
  color: #ffffff;
  border-radius: 16px;
  padding: 2rem 1.5rem;
  text-align: center;
  box-shadow: 0 6px 24px rgba(238, 0, 0, 0.3);
}

.balance-label {
  font-size: 0.8rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.8);
  margin: 0 0 0.5rem;
}

.balance-amount {
  font-size: 2.75rem;
  font-weight: 700;
  margin: 0 0 0.5rem;
  line-height: 1;
  letter-spacing: -0.02em;
}

.account-number {
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
  font-weight: 500;
}

/* Sections */
.section {
  background: var(--color-surface);
  border-radius: 12px;
  padding: 1.25rem;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  border: 1px solid var(--color-border);
}

.section-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--color-text);
  margin: 0 0 1rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

/* Send money button */
.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.8rem 1.5rem;
  background: #ee0000;
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-family: inherit;
  font-size: 1rem;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 0.2s ease;
  letter-spacing: 0.01em;
}

.action-btn:hover {
  background: #cc0000;
}

.action-btn:active {
  background: #aa0000;
}

/* Empty state */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1.5rem 1rem;
  gap: 0.5rem;
}

.empty-icon {
  color: var(--color-border);
  margin-bottom: 0.5rem;
}

.empty-text {
  font-size: 0.95rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  margin: 0;
}

.empty-hint {
  font-size: 0.8rem;
  color: var(--color-text-secondary);
  margin: 0;
  opacity: 0.7;
  text-align: center;
}
</style>
