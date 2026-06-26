<template>
  <header class="app-header">
    <div class="header-left">
      <svg
        class="rh-logo"
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 30 30"
        width="30"
        height="30"
        aria-label="Red Hat logo"
      >
        <path
          fill="#ee0000"
          d="M21.3 14.7c1.3 0 3.3-.2 3.3-1.7 0-.1 0-.2-.1-.3l-1.2-4.2C23 7.7 22.3 7 19.5 5.7 17.2 4.6 14 4 12.8 4c-1.1 0-1.4.5-2.7.5-1.2 0-2.2-.7-3.3-.7-.9 0-1.4.5-1.8 1.6 0 0-1.1 3.2-1.2 3.5-.1.2-.1.4-.1.5 0 1.7 2.6 2.8 5.8 2.8.8 0 1.7-.1 2.6-.3-.2.5-.3 1-.3 1.5 0 .5.1 1 .3 1.5-1.2-.1-2.3-.2-3.2-.2-4.3 0-7.2 1.8-7.2 4.4 0 2.5 2.9 3.9 7.2 3.9 5.2 0 8.1-2.4 8.1-5.5v-.1c0-.1 0-.2-.1-.3.4.1.9.1 1.4.1z"
        />
        <path
          fill="#cc0000"
          d="M24.5 12.7c.3.7.5 1.5.5 2.3 0 3.8-3.3 6.5-9.1 6.5-5.1 0-8.2-1.9-8.2-5 0-3 3-5.4 8.2-5.4.9 0 2 .1 3.1.2.3-.7.4-1.4.4-2.1 0-.5-.1-1.1-.3-1.7.8.3 1.5.6 2.2.9l1.3 4.2c0 .1.1.1-.1.1z"
        />
      </svg>
      <span class="header-title">Red Hat Bank</span>
    </div>

    <div class="header-right">
      <label class="theme-toggle" title="Toggle dark mode">
        <input
          type="checkbox"
          :checked="theme === 'dark'"
          @change="toggleTheme"
        />
        <span class="slider"></span>
      </label>

      <div class="notification-wrapper">
        <svg
          class="bell-icon"
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 24 24"
          width="22"
          height="22"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-label="Notifications"
        >
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        <span class="notification-badge">3</span>
      </div>
    </div>
  </header>
</template>

<script setup>
import { useTheme } from '../composables/useTheme.js'

const { theme, toggleTheme } = useTheme()
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  position: sticky;
  top: 0;
  z-index: 100;
  transition: background-color 0.3s ease;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.rh-logo {
  flex-shrink: 0;
}

.header-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--color-text);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

/* Theme toggle slider */
.theme-toggle {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 20px;
  cursor: pointer;
}

.theme-toggle input {
  opacity: 0;
  width: 0;
  height: 0;
  position: absolute;
}

.slider {
  position: absolute;
  inset: 0;
  background-color: var(--color-border);
  border-radius: 20px;
  transition: background-color 0.3s;
}

.slider::before {
  content: '';
  position: absolute;
  height: 14px;
  width: 14px;
  left: 3px;
  bottom: 3px;
  background-color: #fff;
  border-radius: 50%;
  transition: transform 0.3s;
}

.theme-toggle input:checked + .slider {
  background-color: var(--color-primary);
}

.theme-toggle input:checked + .slider::before {
  transform: translateX(20px);
}

/* Notification bell */
.notification-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.bell-icon {
  color: var(--color-text-secondary);
}

.notification-badge {
  position: absolute;
  top: -5px;
  right: -6px;
  background: var(--color-primary);
  color: #fff;
  font-size: 0.6rem;
  font-weight: 700;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}
</style>
