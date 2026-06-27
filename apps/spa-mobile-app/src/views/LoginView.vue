<template>
  <div class="login-page">
    <div class="login-card">
      <div class="logo-wrap">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 192 145"
          width="60"
          height="60"
          aria-label="Red Hat logo"
        >
          <path d="M157.77,62.61a14,14,0,0,1,.31,3.42c0,14.88-18.1,17.46-30.61,17.46C78.83,83.49,42.53,53.26,42.53,44a6.43,6.43,0,0,1,.22-1.94l-3.66,9.06a18.45,18.45,0,0,0-1.51,7.33c0,18.11,41,45.48,87.74,45.48,20.69,0,36.43-7.76,36.43-21.77,0-1.08,0-1.94-1.73-10.13Z"/>
          <path fill="#ee0000" d="M127.47,83.49c12.51,0,30.61-2.58,30.61-17.46a14,14,0,0,0-.31-3.42l-7.45-32.36c-1.72-7.12-3.23-10.35-15.73-16.6C124.89,8.69,103.76.5,97.51.5,91.69.5,90,8,83.06,8c-6.68,0-11.64-5.6-17.89-5.6-6,0-9.91,4.09-12.93,12.5,0,0-8.41,23.72-9.49,27.16A6.43,6.43,0,0,0,42.53,44c0,9.22,36.3,39.45,84.94,39.45M160,72.07c1.73,8.19,1.73,9.05,1.73,10.13,0,14-15.74,21.77-36.43,21.77C78.54,104,37.58,76.6,37.58,58.49a18.45,18.45,0,0,1,1.51-7.33C22.27,52,.5,55,.5,74.22c0,31.48,74.59,70.28,133.65,70.28,45.28,0,56.7-20.48,56.7-36.65,0-12.72-11-27.16-30.83-35.78"/>
        </svg>
      </div>

      <h1 class="bank-title">Red Hat Bank</h1>
      <p class="tagline">Secure payments powered by OpenShift</p>

      <button class="login-btn" @click="handleLogin" :disabled="loading">
        <span v-if="loading">Redirecting&hellip;</span>
        <span v-else>Sign in with Keycloak</span>
      </button>

      <p v-if="error" class="error">{{ error }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useKeycloak } from '../composables/useKeycloak.js'

const { login } = useKeycloak()
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  loading.value = true
  error.value = ''
  try {
    await login()
  } catch (e) {
    error.value = 'Login failed. Please try again.'
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
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
  border-radius: 16px;
  padding: 2.5rem 2rem;
  width: 100%;
  max-width: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}

.logo-wrap {
  background: #f4f4f4;
  border-radius: 12px;
  padding: 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 1.5rem;
}

.bank-title {
  font-size: 1.6rem;
  font-weight: 700;
  color: #000000;
  margin: 0 0 0.5rem;
  text-align: center;
}

.tagline {
  font-size: 0.9rem;
  color: #6a6a6a;
  margin: 0 0 2rem;
  text-align: center;
  line-height: 1.4;
}

.login-btn {
  background: #ffffff;
  color: #ee0000;
  border: 2px solid #ee0000;
  padding: 0.8rem 2rem;
  border-radius: 25px;
  font-family: inherit;
  font-size: 1rem;
  font-weight: 700;
  cursor: pointer;
  width: 100%;
  transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
  letter-spacing: 0.01em;
}

.login-btn:hover:not(:disabled) {
  background: #ee0000;
  color: #ffffff;
}

.login-btn:active:not(:disabled) {
  background: #cc0000;
  border-color: #cc0000;
  color: #ffffff;
}

.login-btn:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.error {
  margin-top: 1rem;
  font-size: 0.875rem;
  color: #ee0000;
  text-align: center;
  font-weight: 500;
}
</style>
