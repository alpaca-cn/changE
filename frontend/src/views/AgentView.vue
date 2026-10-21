<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { chat, getAgentConfig, history, undo, type AgentConfig, type AgentMessage } from '../api/agent'

const router = useRouter()

const config = ref<AgentConfig | null>(null)
const messages = ref<AgentMessage[]>([])
const draft = ref('')
const sending = ref(false)
const error = ref<string | null>(null)
const undoNote = ref<string | null>(null)
const scroller = ref<HTMLElement | null>(null)

const configured = computed(() => config.value?.configured === true)
const canSend = computed(() => configured.value && draft.value.trim() !== '' && !sending.value)

function reason(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 一条消息里记录的工具名。后端存的是 JSON 数组字符串（没调工具时为 null）。 */
function toolsOf(m: AgentMessage): string[] {
  if (!m.tool_calls) return []
  try {
    const v = JSON.parse(m.tool_calls)
    return Array.isArray(v) ? v.map(String) : []
  } catch {
    return []
  }
}

async function scrollToBottom() {
  await nextTick()
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}

async function load() {
  try {
    config.value = await getAgentConfig()
    if (configured.value) {
      messages.value = await history()
      await scrollToBottom()
    }
    error.value = null
  } catch (e) {
    error.value = reason(e)
  }
}

async function send() {
  const text = draft.value.trim()
  if (text === '' || sending.value) return

  // 先把用户这句放上屏（后端也会存一条），再等回答。
  // 不做乐观更新的坏处是空白等两秒，不知道有没有发出去。
  messages.value.push({
    role: 'user', content: text, tool_calls: null, created_at: new Date().toISOString(),
  })
  draft.value = ''
  sending.value = true
  error.value = null
  undoNote.value = null
  await scrollToBottom()

  try {
    const r = await chat(text)
    messages.value.push({
      role: 'assistant',
      content: r.reply,
      tool_calls: r.tools.length > 0 ? JSON.stringify(r.tools) : null,
      created_at: new Date().toISOString(),
    })
  } catch (e) {
    // 失败的一句话也要留在对话里：否则用户重试时会重复发一次已经成功的部分
    messages.value.push({
      role: 'assistant', content: '', tool_calls: null, created_at: new Date().toISOString(),
    })
    messages.value.pop()
    error.value = reason(e)
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

async function doUndo() {
  undoNote.value = null
  try {
    const r = await undo()
    undoNote.value = r.message
  } catch (e) {
    error.value = reason(e)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page">
    <p class="eyebrow">助手</p>
    <h2>智能体对话</h2>

    <!-- 没配模型时不给输入框：让用户敲完一句话再告诉他不能用，是最烦的失败方式 -->
    <div v-if="config !== null && !configured" class="empty">
      <p>还没有配置模型，对话用不了。</p>
      <p class="hint">去「设置」里填接口地址、模型名和 API key。key 只存在你这台机器上。</p>
      <button class="primary" @click="router.push('/settings')">去设置</button>
    </div>

    <template v-else>
      <div ref="scroller" class="chat">
        <p v-if="messages.length === 0" class="hint">
          试着说：「今天卧推 60 公斤做了 3 组，每组 8 个」，或者「这周练得怎么样」。
        </p>

        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="m.role === 'user' ? 'me' : 'ai'"
        >
          <p class="text">{{ m.content }}</p>
          <!-- 调了哪些工具要露出来：用户有权知道这一句话到底动了什么 -->
          <p v-if="toolsOf(m).length > 0" class="tools">
            调用了：{{ toolsOf(m).join('、') }}
          </p>
        </div>

        <p v-if="sending" class="hint">正在想…</p>
      </div>

      <p v-if="error" class="err">{{ error }}</p>
      <p v-if="undoNote" class="ok">{{ undoNote }}</p>

      <div class="inbox">
        <input
          v-model="draft"
          type="text"
          :disabled="!configured"
          placeholder="说一句话…"
          @keyup.enter="send"
        />
        <button class="primary" :disabled="!canSend" @click="send">发送</button>
        <button class="undo" title="回退智能体最近一次改动" @click="doUndo">撤销上一步</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.page { padding: var(--gap-xl); max-width: 820px; display: flex; flex-direction: column; height: 100%; }
h2 { font-size: 26px; margin: var(--gap-sm) 0 var(--gap-md); }

.empty { margin-top: var(--gap-lg); }
.empty p { margin: 0 0 var(--gap-sm); }
.hint { color: var(--text-dim); font-size: 12px; line-height: 1.7; margin: 0 0 var(--gap-sm); }
.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
.primary:disabled { border-color: var(--line); color: var(--text-dim); cursor: not-allowed; }

.chat {
  flex: 1 1 auto;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--gap-sm);
  padding: var(--gap-md) 0;
}

.msg { max-width: 78%; padding: 10px 13px; border-radius: var(--radius); font-size: 13px; line-height: 1.7; }
.me { align-self: flex-end; background: var(--pill); color: var(--pill-ink); }
.ai { align-self: flex-start; background: var(--panel); border: var(--line-width) solid var(--line); }
.text { margin: 0; white-space: pre-wrap; }

.tools { margin: 6px 0 0; font-size: 11px; color: var(--eyebrow); font-family: var(--mono); }

.inbox {
  display: flex;
  gap: var(--gap-sm);
  align-items: center;
  padding-top: var(--gap-md);
  border-top: var(--line-width) solid var(--line);
}
.inbox input {
  flex: 1 1 auto;
  font: inherit;
  padding: 8px 10px;
  color: var(--text);
  background: var(--panel);
  border: var(--line-width) solid var(--line);
  border-radius: var(--radius-sm);
}
.inbox input:focus { outline: none; border-color: var(--accent); }
.undo { color: var(--text-dim); }

.err { color: var(--danger); font-size: 12px; margin: var(--gap-sm) 0 0; }
.ok { color: var(--ok); font-size: 12px; margin: var(--gap-sm) 0 0; }
</style>
