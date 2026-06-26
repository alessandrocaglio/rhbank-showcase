<template>
  <div class="login-container">
    <div class="login-card">
      <!-- Red Hat SVG logo -->
      <svg
        class="rh-logo"
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 50 50"
        width="60"
        height="60"
        aria-label="Red Hat logo"
      >
        <path
          fill="#ee0000"
          d="M35.5 24.5c2.2 0 5.5-.3 5.5-2.8 0-.2 0-.3-.1-.5L39 14.1C38.3 12.8 37 12 32.5 9.5 29.3 7.7 24 6.7 22 6.7c-1.8 0-2.3.8-4.5.8-2 0-3.7-1.2-5.5-1.2-1.5 0-2.3.8-3 2.7 0 0-1.8 5.3-2 5.8-.1.3-.2.6-.2.8 0 2.8 4.3 4.7 9.7 4.7 1.3 0 2.8-.2 4.3-.5-.3.8-.5 1.7-.5 2.5 0 .8.2 1.7.5 2.5-2-.2-3.8-.3-5.3-.3-7.2 0-12 3-12 7.3 0 4.2 4.8 6.5 12 6.5 8.7 0 13.5-4 13.5-9.2v-.2c0-.1 0-.3-.1-.4.7.2 1.5.2 2.3.2z"
        />
        <path
          fill="#cc0000"
          d="M41 21.2c.5 1.2.8 2.5.8 3.8 0 6.3-5.5 10.8-15.2 10.8-8.5 0-13.7-3.2-13.7-8.3 0-5 5-9 13.7-9 1.5 0 3.3.2 5.2.3.5-1.2.7-2.3.7-3.5 0-.8-.2-1.8-.5-2.8 1.3.5 2.5 1 3.7 1.5l2.2 7c-.1.1 0 .2.1.2z"
        />
      </svg>

      <h2 class="bank-name">Red Hat Bank</h2>
      <h1 class="welcome-text">WELCOME!</h1>
      <p class="subtitle">Your secure banking experience</p>

      <button class="login-btn" @click="handleLogin" :disabled="loading">
        <span v-if="!loading">Login with Keycloak</span>
        <span v-else>Redirecting...</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useKeycloak } from '../composables/useKeycloak.js'

const { login } = useKeycloak()
const loading = ref(false)

async function handleLogin() {
  loading.value = true
  try {
    await login()
  } catch (err) {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #ee0000;
  padding: 2rem 1rem;
  min-height: 100%;
}

.login-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 2.5rem 2rem;
  width: 100%;
  max-width: 340px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.rh-logo {
  margin-bottom: 0.5rem;
}

.bank-name {
  font-size: 1.2rem;
  font-weight: 700;
  color: #000000;
  margin: 0;
}

.welcome-text {
  font-size: 2rem;
  font-weight: 700;
  color: #ee0000;
  margin: 0.25rem 0;
  letter-spacing: 0.05em;
}

.subtitle {
  font-size: 0.9rem;
  color: #6a6a6a;
  margin-bottom: 1.5rem;
  text-align: center;
}

.login-btn {
  background: #ffffff;
  color: #ee0000;
  border: 2px solid #ee0000;
  padding: 0.75rem 2rem;
  border-radius: 25px;
  font-family: inherit;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  width: 100%;
  transition: background-color 0.2s, color 0.2s;
  margin-top: 0.5rem;
}

.login-btn:hover:not(:disabled) {
  background: #ee0000;
  color: #ffffff;
}

.login-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
