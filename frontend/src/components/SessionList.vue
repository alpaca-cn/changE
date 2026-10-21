<script setup lang="ts">
import type { SessionSummary } from '../api/training'

// 今日页的「最近几次」与训练记录页的「历史」共用这一个组件。
// `select` 由训练记录页用来打开某次训练的详情；今日页不监听它（只是列表）。
defineProps<{ sessions: SessionSummary[]; error?: string | null }>()
const emit = defineEmits<{ (e: 'select', id: number): void }>()

/**
 * 标题为空时不要留白：用户看到一格没有名字的卡片，会以为数据坏了。
 * 用「（未命名）」明确表示「这次训练你没起名」，而不是「我们没拿到」。
 */
function titleOf(s: SessionSummary): string {
  return s.title !== null && s.title.trim() !== '' ? s.title : '（未命名）'
}

/** 最重那一组的文案。**自重动作没有重量**，不显示这一行，而不是显示 0kg。 */
function topOf(s: SessionSummary): string | null {
  // `!= null` 同时挡住 null 与 undefined：老数据 / 老夹具里这两个都可能
  if (s.topWeight == null) return null
  const name = s.topExerciseName === null || s.topExerciseName === undefined ? '' : s.topExerciseName
  return `${name} ${s.topWeight}kg`.trim()
}
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
          <span class="head">
            <span class="title">{{ titleOf(s) }}</span>
            <span v-if="s.perceivedState" class="state">{{ s.perceivedState }}</span>
          </span>

          <span class="date">{{ s.sessionDate }}</span>

          <span class="stats">
            <span class="stat"><b>{{ s.exercises }}</b> 个动作</span>
            <span class="stat"><b>{{ s.sets }}</b> 组</span>
          </span>

          <span v-if="topOf(s) !== null" class="top">{{ topOf(s) }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.sessions { margin-top: var(--gap-lg); }
h3 { font-size: 15px; }

/* 卡片网格，不是文字行。参考的是「一周训练日」那种排布：
   一眼看得到练了哪几天、每次多少量，而不是读一串日期。 */
.list {
  list-style: none;
  margin: var(--gap-md) 0 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: var(--gap-md);
}

.session-item { min-width: 0; }

.session {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  height: 100%;
  text-align: left;
  padding: var(--gap-md);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  color: var(--text);
  cursor: pointer;
}

.session:hover { border-color: var(--accent); }

.head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.title { font-weight: 600; font-size: 14px; }

/* 状态标签：一行小字，压在标题右边 */
.state {
  flex: none;
  font-size: 10px;
  color: var(--eyebrow);
  border: var(--line-width) solid var(--line);
  border-radius: 999px;
  padding: 1px 7px;
}

.date {
  color: var(--text-dim);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.stats { display: flex; gap: var(--gap-md); margin-top: 2px; }
.stat { font-size: 11px; color: var(--text-dim); }
.stat b { font-size: 15px; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }

/* 最重那一组：这是卡片里唯一的「成绩」，用强调色把它挑出来 */
.top {
  margin-top: 4px;
  font-size: 11px;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}

.err { color: var(--danger); }
.dim { color: var(--text-dim); }
</style>
