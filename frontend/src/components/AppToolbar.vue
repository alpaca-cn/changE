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
  height: 66px;
  padding: 0 30px;
  border-bottom: var(--line-width) solid var(--line);
}

/* 面包屑前缀用 ::before 而不是再写一个节点：
   h1 的文本必须保持「就是页名」，否则工具条的测试与读屏都会多出一个产品名。 */
h1 { font-size: 15px; }

h1::before {
  content: 'changE / ';
  color: var(--eyebrow);
  font-weight: 400;
  letter-spacing: 0.08em;
}
</style>
