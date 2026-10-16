<script setup lang="ts">
import { NAV_GROUPS } from '../nav'

const top = NAV_GROUPS.filter((g) => !g.pinnedBottom)
const bottom = NAV_GROUPS.filter((g) => g.pinnedBottom)
</script>

<template>
  <nav class="sidebar">
    <div class="brand">changE</div>

    <div class="scroll">
      <div v-for="group in top" :key="group.label" class="nav-group">
        <p class="group-label eyebrow">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
        >
          <span class="glyph">{{ item.glyph }}</span>
          <span class="label">{{ item.label }}</span>
          <span v-if="item.marker" class="marker">{{ item.marker }}</span>
        </RouterLink>
      </div>
    </div>

    <!-- 「我的」钉底部，用一条分隔线与功能区隔开 -->
    <div v-for="group in bottom" :key="group.label" class="nav-group pinned">
      <div class="divider" />
      <p class="group-label eyebrow">{{ group.label }}</p>
      <RouterLink
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="nav-item"
      >
        <span class="glyph">{{ item.glyph }}</span>
        <span class="label">{{ item.label }}</span>
      </RouterLink>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  width: 208px;
  flex: 0 0 208px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-right: var(--line-width) solid var(--line);
}

.brand {
  padding: var(--gap-lg) var(--gap-md) var(--gap-md);
  font-size: 17px;
  font-weight: var(--title-weight);
  letter-spacing: var(--title-tracking);
}

.scroll { flex: 1 1 auto; overflow-y: auto; }

.nav-group { padding: var(--gap-md) var(--gap-sm) 0; }

.group-label { margin: 0 0 var(--gap-sm) var(--gap-sm); }

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  padding: 7px var(--gap-sm);
  margin-bottom: 2px;
  border-radius: var(--radius);
  color: var(--text);
  text-decoration: none;
}

.nav-item:hover { background: var(--bg); }

/* 选中态用强调色，但不填充整块——设计文档要求靠描边与间距分层 */
.nav-item.router-link-active {
  color: var(--accent);
  background: var(--bg);
}

.glyph {
  width: 20px;
  text-align: center;
  color: var(--text-dim);
  font-size: 12px;
}

.nav-item.router-link-active .glyph { color: var(--accent); }

.label { flex: 1 1 auto; }

.marker {
  font-size: 10px;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius);
  padding: 1px 5px;
}

.divider {
  height: var(--line-width);
  background: var(--line);
  margin: var(--gap-sm) var(--gap-sm) var(--gap-md);
}

.nav-group.pinned { padding-bottom: var(--gap-md); }
</style>
