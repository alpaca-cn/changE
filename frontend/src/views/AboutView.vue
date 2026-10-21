<script setup lang="ts">
import painting from '../assets/about/oil-painting.jpg'
import { ref } from 'vue'
import {
  APP_VERSION,
  BUILD_TIME,
  buildTimeText,
  checkForUpdate,
  type UpdateCheck,
} from '../api/version'

/**
 * 关于与致谢。
 *
 * 两件事在这里交代：
 *   1. 数据源的许可证署名 —— 设计文档「七、数据源与许可证」要求，而且这些数据真的
 *      随安装包发出去，不署名是违约。
 *   2. 两张用户提供的图 —— 它们会随安装包发给朋友，所以来源要写清楚，
 *      免得下一个维护者以为是从网上扒的。
 */
/** 版本与更新检查。 */
const checking = ref(false)
const check = ref<UpdateCheck | null>(null)

async function runCheck() {
  checking.value = true
  try {
    check.value = await checkForUpdate()
  } finally {
    checking.value = false
  }
}

const sources = [
  { name: '动作库主体 · 876 条', from: 'yuhonas/free-exercise-db', license: 'Unlicense（公有领域）' },
  { name: '中文说明 · 1,324 条（交叉校验用）', from: 'hasaneyldrm/exercises-dataset', license: 'MIT（仅文本）' },
  { name: '人体图区域路径 · 65 个', from: 'vulovix/body-muscles', license: 'Apache-2.0' },
]
</script>

<template>
  <section class="page">
    <p class="eyebrow">我的</p>
    <h2>关于与致谢</h2>

    <div class="hero">
      <img :src="painting" alt="训练者的油画" />
      <div class="cap">
        <p class="who">本项目的两幅配图由作者本人提供</p>
        <p class="note">它们随安装包一起分发，仅用于界面装饰。</p>
      </div>
    </div>

    <section class="block">
      <h3>数据来源</h3>
      <p class="hint">
        这三份数据随安装包一起发到你的机器上，所以许可证要求署名。
      </p>
      <ul class="sources">
        <li v-for="s in sources" :key="s.name">
          <span class="nm">{{ s.name }}</span>
          <span class="from">{{ s.from }}</span>
          <span class="lic">{{ s.license }}</span>
        </li>
      </ul>
    </section>

    <section class="block">
      <h3>版本</h3>
      <p class="hint">
        当前 <b class="mono">{{ APP_VERSION }}</b> · 构建于 <span class="mono">{{ buildTimeText(BUILD_TIME) }}</span>
      </p>

      <button class="primary" :disabled="checking" @click="runCheck">
        {{ checking ? '正在检查…' : '检查更新' }}
      </button>

      <div v-if="check !== null" class="check">
        <p v-if="check.kind === 'no-source'" class="hint">
          这个发行版还没有配置更新源，所以查不到新版。
          （构建时把 <code>UPDATE_MANIFEST</code> 填成一个返回
          <code>{ version, notes?, url? }</code> 的地址即可启用。）
        </p>
        <p v-else-if="check.kind === 'up-to-date'" class="ok">
          已经是最新的（{{ check.current }}）。
        </p>
        <p v-else-if="check.kind === 'available'" class="ok">
          有新版本 <b>{{ check.latest }}</b>（当前 {{ check.current }}）。
        </p>
        <p v-else class="err">检查失败：{{ check.message }}</p>

        <template v-if="check.kind === 'available'">
          <p v-if="check.notes !== ''" class="hint">{{ check.notes }}</p>
          <code v-if="check.url !== ''" class="path">{{ check.url }}</code>
        </template>
      </div>

      <p class="hint tip">
        想要「装一次就自动更新」还需要一对签名密钥与一个能放清单的地址——
        那是部署决定，不是代码问题。现在这一步只回答「我是不是最新的」。
      </p>
    </section>

    <section class="block">
      <h3>关于这个项目</h3>
      <p class="hint">
        changE 是一个只在本地跑的健身记录工具。数据存在你自己的
        <code>%APPDATA%\\changE</code> 里，不上传、不同步、不需要账号。
        智能体对话要用你自己的模型 key——它也只存在那台机器上。
      </p>
    </section>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 760px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }

/* 竖幅油画整幅放，不裁：它本身就是一个成品，裁了就只剩构图碎片 */
.hero { display: flex; gap: var(--gap-lg); align-items: flex-start; margin-top: var(--gap-md); }
.hero img {
  width: 260px;
  height: auto;
  display: block;
  border-radius: var(--radius);
  border: var(--line-width) solid var(--line);
}
.cap { padding-top: var(--gap-sm); }
.cap .who { margin: 0; font-size: 13px; }
.cap .note { margin: 6px 0 0; font-size: 11px; color: var(--eyebrow); line-height: 1.7; }

.block { margin-top: var(--gap-xl); }
h3 { font-size: 15px; }
.hint { color: var(--text-dim); font-size: 12px; line-height: 1.8; margin: var(--gap-sm) 0 var(--gap-md); }
.hint code { font-family: var(--mono); font-size: 11px; }

.sources { list-style: none; margin: 0; padding: 0; border-top: var(--line-width) solid var(--line); }
.sources li {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px var(--gap-md);
  padding: 10px 0;
  border-bottom: var(--line-width) solid var(--line);
  font-size: 12px;
}
.nm { font-weight: 500; }
.from { color: var(--text-dim); font-family: var(--mono); font-size: 11px; }
.lic { grid-column: 2; text-align: right; color: var(--eyebrow); font-size: 11px; }

.mono { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.check { margin-top: var(--gap-md); }
.check .ok { color: var(--ok); font-size: 12px; }
.check .err { color: var(--danger); font-size: 12px; }
.tip { margin-top: var(--gap-md); color: var(--eyebrow); }
.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.primary:disabled { border-color: var(--line); color: var(--text-dim); cursor: not-allowed; }
</style>
