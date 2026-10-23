<script setup lang="ts">
import { NAV_GROUPS } from '../nav'

const top = NAV_GROUPS.filter((g) => !g.pinnedBottom)
const bottom = NAV_GROUPS.filter((g) => g.pinnedBottom)
</script>

<template>
  <nav class="sidebar glass">
    <!-- 品牌块：中文名 + 一行 8px 全大写字距标签。参考项目用的是 CSS ::before，
         这里用真节点，因为它是可被读屏读到的产品名。 -->
    <div class="brand">
      <div class="nm">chang<i>E</i></div>
      <div class="cap">Personal Training System</div>
    </div>

    <div class="scroll">
      <div v-for="group in top" :key="group.label" class="nav-group">
        <p class="group-label eyebrow">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-item"
        >
          <span class="no">{{ item.no }}</span>
          <span class="tw">
            <b>{{ item.label }}</b>
            <em>{{ item.labelEn }}</em>
          </span>
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
        <span class="no">{{ item.no }}</span>
        <span class="tw">
          <b>{{ item.label }}</b>
          <em>{{ item.labelEn }}</em>
        </span>
      </RouterLink>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  width: 224px;
  flex: 0 0 224px;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 34px 22px 22px;
  background: var(--sidebar);
  border-right: var(--line-width) solid var(--line);
}

.brand { padding: 0 8px 30px; }

.brand .nm {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1.02;
}

/* 只给最后一个字母上铜色，是一个很小的记忆点；其余靠字重与字距 */
.brand .nm i { font-style: normal; color: var(--accent); }

.brand .cap {
  margin-top: 6px;
  font-size: 8px;
  font-weight: 600;
  letter-spacing: var(--eyebrow-tracking);
  text-transform: uppercase;
  color: var(--eyebrow);
}

.scroll { flex: 1 1 auto; overflow-y: auto; }

.nav-group { padding: 10px 0 0; }

/* 分组标题保留（9 个入口不分组会更难扫），但压成 8px 全大写：
   它只是分区标记，不该和入口抢注意力。 */
.group-label {
  margin: 0 0 6px 8px;
  font-size: 8px;
  letter-spacing: var(--eyebrow-tracking);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 13px;
  color: var(--text-dim);
  text-decoration: none;
  position: relative;
}

.nav-item:hover { background: var(--bg); }

/* 选中态是**反色深底药丸**，不是变色文字。
   浅色页面上，一块深底比一行彩色字稳重得多，也更容易一眼定位。 */
.nav-item.router-link-active {
  background: var(--pill);
  color: var(--pill-ink);
}

/* 左侧那条铜色竖条：整块深底之外还留一个色相，用来呼应强调色 */
.nav-item.router-link-active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: var(--accent);
}

.no {
  flex: none;
  width: 16px;
  font-family: var(--mono);
  font-size: 10px;
  color: var(--eyebrow);
}

.tw {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.tw b {
  font-weight: 500;
  font-size: 13px;
  line-height: 1.35;
}

.tw em {
  font-style: normal;
  font-size: 8px;
  font-weight: 600;
  letter-spacing: var(--eyebrow-tracking);
  text-transform: uppercase;
  color: var(--eyebrow);
}

/* 反色药丸里的编号与英文要跟着翻，否则深底上读不出来 */
.nav-item.router-link-active .no,
.nav-item.router-link-active .tw em { color: rgba(246, 244, 239, 0.5); }

.marker {
  margin-left: auto;
  font-size: 10px;
  letter-spacing: var(--eyebrow-tracking);
  color: var(--eyebrow);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius-sm);
  padding: 1px 5px;
}

.divider {
  height: var(--line-width);
  background: var(--line);
  margin: 6px 8px 12px;
}

.nav-group.pinned { padding-bottom: 2px; }
</style>
