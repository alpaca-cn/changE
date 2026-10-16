<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppToolbar from './components/AppToolbar.vue'
import BackendStatusBar from './components/BackendStatusBar.vue'
</script>

<template>
  <div class="shell">
    <AppSidebar />
    <div class="main">
      <AppToolbar />
      <main class="workspace">
        <RouterView />
      </main>
      <BackendStatusBar />
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100%; }
.main { flex: 1 1 auto; display: flex; flex-direction: column; min-width: 0; }
.workspace { flex: 1 1 auto; overflow-y: auto; }
</style>
