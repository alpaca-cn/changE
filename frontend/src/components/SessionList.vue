<script setup lang="ts">
import type { SessionSummary } from '../api/training'

// 今日页的「最近几次」与训练记录页的「历史」共用这一个组件。
defineProps<{ sessions: SessionSummary[]; error?: string | null }>()
</script>

<template>
  <section class="sessions">
    <h3>最近训练</h3>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-else-if="sessions.length === 0" class="dim">还没有训练记录</p>

    <ul v-else class="list">
      <li v-for="s in sessions" :key="s.id" class="session">
        <span class="date">{{ s.sessionDate }}</span>
        <span class="title">{{ s.title || '（未命名）' }}</span>
        <span class="state">{{ s.perceivedState || '' }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.sessions { margin-top: var(--gap-lg); }
h3 { font-size: 15px; }

.list {
  list-style: none;
  margin: var(--gap-md) 0 0;
  padding: 0;
  border-top: var(--line-width) solid var(--line);
}

.session {
  display: grid;
  grid-template-columns: 120px 1fr auto;
  gap: var(--gap-md);
  padding: 7px 0;
  border-bottom: var(--line-width) solid var(--line);
}

.date { color: var(--text-dim); font-variant-numeric: tabular-nums; }
.title { font-weight: 600; }
.state { color: var(--text-dim); font-size: 12px; }

.err { color: var(--danger); }
.dim { color: var(--text-dim); }
</style>
