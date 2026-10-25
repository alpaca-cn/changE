<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAgentConfig, putAgentConfig, type AgentConfig } from '../api/agent'
import {
  backupInfo,
  createBackup,
  humanSize,
  listBackups,
  type Backup,
  type BackupInfo,
} from '../api/backup'

/**
 * 设置页。目前只有一块：模型配置。
 *
 * **key 一旦写进去就再也读不回来**——后端只回 `configured / baseUrl / model`。
 * 所以这个输入框永远是空的，占位文案必须说清楚（「已保存，留空表示不改」），
 * 否则用户会以为自己的 key 丢了。
 */
const config = ref<AgentConfig | null>(null)
// 服务商预设：换模型时不用自己记接口地址和型号名（各家路径都不一样）
import { LLM_PROVIDERS, providerById } from './llm-providers'

/** 当前选中的预设 id。「自定义」时是 'custom'。 */
const providerId = ref('custom')

/** 选中一个预设：把地址与型号填进表单（仍可手动改）。 */
function applyProvider(id: string) {
  providerId.value = id
  const p = providerById(id)
  if (p === undefined) return
  baseUrl.value = p.baseUrl
  model.value = p.model
}

/** 当前预设给的那句提示（没有预设时为空）。 */
const providerNote = computed(() => {
  const p = providerById(providerId.value)
  return p === undefined ? '' : p.note
})

/** 当前预设是不是**不支持图片**——拍照记饮食那一条会一直失败。 */
const providerLacksVision = computed(() => {
  const p = providerById(providerId.value)
  return p !== undefined && !p.vision
})

const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')

const saving = ref(false)
const error = ref<string | null>(null)
const saved = ref<string | null>(null)

/**
 * 只在真的填了某项时才把它放进请求体。
 *
 * 空串与「这项不动」是两件不同的事：后端把 `null` 当「不动」、把空串当「清除」。
 * 这里一律用「不放进 body」表示不动，于是用户只改模型时不会把 key 清掉。
 */
function payload(): { baseUrl?: string; model?: string; apiKey?: string } {
  const out: { baseUrl?: string; model?: string; apiKey?: string } = {}
  if (baseUrl.value.trim() !== '') out.baseUrl = baseUrl.value.trim()
  if (model.value.trim() !== '') out.model = model.value.trim()
  if (apiKey.value.trim() !== '') out.apiKey = apiKey.value.trim()
  return out
}

const canSave = computed(() => Object.keys(payload()).length > 0 && !saving.value)

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load() {
  try {
    config.value = await getAgentConfig()
    baseUrl.value = config.value.baseUrl
    model.value = config.value.model
    error.value = null
  } catch (e) {
    error.value = reason(e)
  }
}

async function save() {
  saving.value = true
  saved.value = null
  try {
    config.value = await putAgentConfig(payload())
    apiKey.value = ''   // 立刻从内存里清掉——它本来也不该留在这里
    saved.value = config.value.configured
      ? '已保存，可以去对话了。'
      : '还缺一项，三项都填才能用。'
    error.value = null
  } catch (e) {
    error.value = reason(e)
  } finally {
    saving.value = false
  }
}

// ── 数据备份 ──────────────────────────────────────────────
const backups = ref<Backup[]>([])
const info = ref<BackupInfo | null>(null)
const backingUp = ref(false)
const backupError = ref<string | null>(null)
const justMade = ref<Backup | null>(null)

async function loadBackups() {
  try {
    backups.value = await listBackups()
    info.value = await backupInfo()
  } catch {
    // 备份列表读不到不该影响模型配置那块，安静地空着
    backups.value = []
  }
}

async function makeBackup() {
  backingUp.value = true
  backupError.value = null
  try {
    justMade.value = await createBackup()
    await loadBackups()
  } catch (e) {
    backupError.value = reason(e)
  } finally {
    backingUp.value = false
  }
}

onMounted(() => {
  void load()
  void loadBackups()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">我的</p>
    <h2>设置</h2>

    <section class="block">
      <h3>模型</h3>
      <p class="hint">
        智能体对话要用它。<b>key 只保存在你这台机器的数据目录里</b>（<code>agent.json</code>），
        不进安装包、不写进数据库、也不会被回传到界面——保存之后这里就再也读不到它了。
      </p>

      <p v-if="config?.configured" class="ok">已配置 · 模型 {{ config.model }}</p>
      <p v-else class="warn">还没配置，智能体对话用不了。</p>

      <label class="field">
        <span class="label">服务商</span>
        <select class="provider" :value="providerId" @change="applyProvider(($event.target as HTMLSelectElement).value)">
          <option v-for="p in LLM_PROVIDERS" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
        <!-- 不支持图片的要说出来：否则用户会一直以为是自己照片的问题 -->
        <span v-if="providerNote" class="note" :class="{ bad: providerLacksVision }">{{ providerNote }}</span>
      </label>

      <label class="field">
        <span class="label">接口地址</span>
        <input v-model="baseUrl" type="text" placeholder="https://api.deepseek.com/v1" />
        <span class="note">要带版本号，但不要带 /chat/completions。</span>
      </label>

      <label class="field">
        <span class="label">模型名</span>
        <input v-model="model" type="text" placeholder="deepseek-chat" />
      </label>

      <label class="field">
        <span class="label">API Key</span>
        <input
          v-model="apiKey"
          type="password"
          autocomplete="off"
          :placeholder="config?.configured ? '已保存，留空表示不改' : 'sk-…'"
        />
        <span class="note">留空 = 不改。要从零开始，把三项都填上再保存。</span>
      </label>

      <p v-if="error" class="err">{{ error }}</p>
      <p v-if="saved" class="ok">{{ saved }}</p>

      <button class="primary" :disabled="!canSave" @click="save">保存</button>
    </section>

    <section class="block">
      <h3>数据备份</h3>
      <p class="hint">
        这是本地应用——没有云、没有同步。硬盘坏了或文件夹被删，数据就没了。
        备份是把整个库导出成一个独立文件：<b>导出来之后还要拷到别的地方</b>，留在同一块盘上不算备份。
      </p>

      <button class="primary" :disabled="backingUp" @click="makeBackup">
        {{ backingUp ? '正在导出…' : '立即备份' }}
      </button>

      <p v-if="backupError" class="err">{{ backupError }}</p>

      <div v-if="justMade !== null" class="made">
        <p class="ok">已导出（{{ humanSize(justMade.bytes) }}）：</p>
        <code class="path">{{ justMade.path }}</code>
        <p v-if="!justMade.inDocuments" class="warn">
          注意：没找到「文档」文件夹，备份落在了数据目录旁边。请把它拷到别处。
        </p>
      </div>

      <div v-if="backups.length > 0" class="recent">
        <p class="label">最近的备份</p>
        <ul>
          <li v-for="b in backups.slice(0, 5)" :key="b.path">
            <span class="mono">{{ humanSize(b.bytes) }}</span>
            <code>{{ b.path }}</code>
          </li>
        </ul>
      </div>

      <p v-if="info !== null" class="hint restore">{{ info.note }}</p>
    </section>
  </section>
</template>

<style scoped>
.page {
  margin-inline: auto; padding: var(--gap-xl); max-width: 960px; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }

.block { margin-top: var(--gap-lg); }
h3 { font-size: 15px; }
.hint { color: var(--text-dim); font-size: 12px; line-height: 1.7; margin: var(--gap-sm) 0 var(--gap-md); }
.hint code { font-family: var(--mono); font-size: 11px; }

.provider { font-size: 13px; }
.note.bad { color: var(--danger); }

.field { display: block; margin-bottom: var(--gap-md); }
.label { display: block; font-size: 12px; color: var(--text-dim); margin-bottom: 5px; }
.note { display: block; font-size: 11px; color: var(--eyebrow); margin-top: 4px; }

input {
  width: 100%;
  font: inherit;
  padding: 8px 10px;
  color: var(--text);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius-sm);
}
input:focus { outline: none; border-color: var(--accent); }

.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.primary:disabled { border-color: var(--line); color: var(--text-dim); cursor: not-allowed; }

.ok { color: var(--ok); font-size: 12px; }
.warn { color: var(--warn); font-size: 12px; }
.err { color: var(--danger); font-size: 12px; }

.made { margin-top: var(--gap-md); }
.path {
  display: block;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--text);
  background: var(--bg);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  margin-top: 4px;
  word-break: break-all;
}
.recent { margin-top: var(--gap-md); }
.recent ul { list-style: none; margin: 4px 0 0; padding: 0; }
.recent li {
  display: flex;
  gap: var(--gap-sm);
  align-items: baseline;
  padding: 5px 0;
  border-bottom: var(--line-width) solid var(--line);
  font-size: 11px;
}
.recent li code { font-family: var(--mono); color: var(--text-dim); word-break: break-all; }
.restore { margin-top: var(--gap-md); }
</style>
