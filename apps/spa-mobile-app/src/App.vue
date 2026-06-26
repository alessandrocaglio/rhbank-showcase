<template>
  <AppHeader />
  <main class="content">
    <router-view />
  </main>
  <BottomNav />
</template>

<script setup>
import AppHeader from './components/AppHeader.vue'
import BottomNav from './components/BottomNav.vue'
</script>

<style scoped>
.content {
  flex: 1;
  overflow-y: auto;
}
</style>
