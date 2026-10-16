<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { allRoutes } from '../nav'

const route = useRoute()

// 页名从 nav.ts 查，不在模板里手写——布局改了这里不用动。
const title = computed(
  () => allRoutes().find((r) => r.path === route.path)?.label ?? 'changE',
)
</script>

<template>
  <header class="toolbar">
    <h1>{{ title }}</h1>
  </header>
</template>

<style scoped>
.toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  height: 46px;
  padding: 0 var(--gap-lg);
  border-bottom: var(--line-width) solid var(--line);
}

h1 { font-size: 15px; }
</style>
