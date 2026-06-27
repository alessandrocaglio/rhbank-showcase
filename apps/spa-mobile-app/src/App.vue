<template>
  <AppHeader v-if="!isLoginPage" />
  <main class="content" :class="{ 'full-page': isLoginPage }">
    <router-view />
  </main>
  <BottomNav v-if="!isLoginPage" />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import BottomNav from './components/BottomNav.vue'

const route = useRoute()
const isLoginPage = computed(() => route.path === '/login' || route.path === '/')
</script>

<style scoped>
.content {
  flex: 1;
  overflow-y: auto;
}
.full-page {
  display: flex;
  flex-direction: column;
}
</style>
