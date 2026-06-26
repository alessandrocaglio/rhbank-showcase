import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { initiatePayment } from '../api/payments.js'

export function usePayment() {
  const router = useRouter()

  const form = reactive({
    sourceAccount: '',
    destinationAccount: '',
    amount: '',
    currency: 'USD',
    type: 'INSTANT',
  })

  const errors = reactive({
    sourceAccount: '',
    destinationAccount: '',
    amount: '',
  })

  const isLoading = ref(false)
  const errorMessage = ref('')

  function validate() {
    let valid = true
    errors.sourceAccount = form.sourceAccount.trim() ? '' : 'Source account is required'
    errors.destinationAccount = form.destinationAccount.trim() ? '' : 'Destination account is required'

    const amt = parseFloat(form.amount)
    if (!form.amount || isNaN(amt) || amt <= 0) {
      errors.amount = 'Amount must be a positive number'
      valid = false
    } else {
      errors.amount = ''
    }

    if (errors.sourceAccount || errors.destinationAccount) valid = false
    return valid
  }

  async function submit() {
    if (!validate()) return

    isLoading.value = true
    errorMessage.value = ''

    try {
      const result = await initiatePayment({
        sourceAccount: form.sourceAccount.trim(),
        destinationAccount: form.destinationAccount.trim(),
        amount: parseFloat(form.amount),
        currency: form.currency,
      })
      router.push(`/payments/${result.transactionId}/status`)
    } catch (err) {
      errorMessage.value = err.message || 'Payment failed. Please try again.'
    } finally {
      isLoading.value = false
    }
  }

  return { form, errors, isLoading, errorMessage, submit }
}
