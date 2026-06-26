<template>
  <div class="payments-wrapper">
    <div class="card payment-card">
      <h2 class="payment-title">Send Money</h2>

      <div class="form-group">
        <label>Source Account</label>
        <input
          v-model="form.sourceAccount"
          class="input-field"
          placeholder="e.g. ACC-001"
        />
        <span v-if="errors.sourceAccount" class="field-error">{{ errors.sourceAccount }}</span>
      </div>

      <div class="form-group">
        <label>Destination Account</label>
        <input
          v-model="form.destinationAccount"
          class="input-field"
          placeholder="e.g. ACC-002"
        />
        <span v-if="errors.destinationAccount" class="field-error">{{ errors.destinationAccount }}</span>
      </div>

      <div class="form-group">
        <label>Amount</label>
        <input
          v-model="form.amount"
          type="number"
          min="0.01"
          step="0.01"
          class="input-field"
          placeholder="0.00"
        />
        <span v-if="errors.amount" class="field-error">{{ errors.amount }}</span>
      </div>

      <div class="form-group">
        <label>Currency</label>
        <select v-model="form.currency" class="input-field">
          <option value="USD">USD</option>
          <option value="EUR">EUR</option>
          <option value="GBP">GBP</option>
        </select>
      </div>

      <div class="form-group">
        <label>Payment Type</label>
        <div class="radio-group">
          <label class="radio-label">
            <input type="radio" v-model="form.type" value="INSTANT" /> Instant
          </label>
          <label class="radio-label">
            <input type="radio" v-model="form.type" value="STANDARD" /> Standard
          </label>
        </div>
      </div>

      <p v-if="errorMessage" class="api-error">{{ errorMessage }}</p>

      <button
        class="btn-primary"
        :disabled="isLoading"
        @click="submit"
      >
        <span v-if="isLoading">Processing...</span>
        <span v-else>Send Payment</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { usePayment } from '../composables/usePayment.js'
const { form, errors, isLoading, errorMessage, submit } = usePayment()
</script>

<style scoped>
.payments-wrapper {
  padding: 1.5rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.payment-card { max-width: 400px; margin: 0 auto; width: 100%; }
.payment-title {
  color: var(--color-primary);
  text-align: center;
  font-size: 1.5rem;
  margin-bottom: 1.5rem;
}
.form-group { margin-bottom: 1.5rem; display: flex; flex-direction: column; gap: 0.4rem; }
.form-group label { font-weight: 500; font-size: 0.9rem; }
.field-error { font-size: 0.75rem; color: var(--color-primary); }
.radio-group { display: flex; gap: 1rem; }
.radio-label { display: flex; align-items: center; gap: 0.4rem; font-weight: 400; }
.api-error {
  color: var(--color-primary);
  font-size: 0.85rem;
  margin-bottom: 1rem;
  text-align: center;
}
</style>
