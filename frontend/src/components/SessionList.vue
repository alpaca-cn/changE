<script setup lang="ts">
import type { SessionSummary } from '../api/training'

// 今日页的「最近几次」与训练记录页的「历史」共用这一个组件。
// `select` 由训练记录页用来打开某次训练的详情；今日页不监听它（只是列表）。
defineProps<{ sessions: SessionSummary[]; error?: string | null }>()
const emit = defineEmits<{ (e: 'select', id: number): void }>()
</script>

<template>
  <section class="sessions">
    <h3>最近训练</h3>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-else-if="sessions.length === 0" class="dim">还没有训练记录</p>

    <ul v-else class="list">
      <li v-for="s in sessions" :key="s.id" class="session-item">
        <!-- 用 button 而不是给 li 挂 click：键盘也能打开详情，不只是一个能点的 div -->
        <button type="button" class="session" @click="emit('select', s.id)">
          <span class="date">{{ s.sessionDate }}</span>
          <span class="title">{{ s.title || '（未命名）' }}</span>
          <span class="state">{{ s.perceivedState || '' }}</span>
        </button>
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

.session-item { border-bottom: var(--line-width) solid var(--line); }

.session {
  display: grid;
  grid-template-columns: 120px 1fr auto;
  gap: var(--gap-md);
  width: 100%;
  text-align: left;
  padding: 7px 0;
  /* 列表项是「文字行」，不是卡片：去掉 button 的默认外观 */
  background: none;
  border: none;
  border-radius: 0;
}

.session:hover .title { color: var(--accent); }

.date { color: var(--text-dim); font-variant-numeric: tabular-nums; }
.title { font-weight: 600; }
.state { color: var(--text-dim); font-size: 12px; }

.err { color: var(--danger); }
.dim { color: var(--text-dim); }
</style>
