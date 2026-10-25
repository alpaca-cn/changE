<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { chat, getAgentConfig, history, undo, type AgentConfig, type AgentMessage } from '../api/agent'
import { toImageDataUrl } from '../lib/image'

const router = useRouter()

const config = ref<AgentConfig | null>(null)
const messages = ref<AgentMessage[]>([])
const draft = ref('')
/** 已选好、已压好的图片（data URL）。发出去之后清空。 */
const image = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const sending = ref(false)
const error = ref<string | null>(null)
const undoNote = ref<string | null>(null)
const scroller = ref<HTMLElement | null>(null)

const configured = computed(() => config.value?.configured === true)
/** 有图时允许不发文字——「拍一张，说一句『记一下』」甚至只说图都该能发。 */
const canSend = computed(
  () => configured.value && (draft.value.trim() !== '' || image.value !== null) && !sending.value,
)

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
  // **防重入。** 2026-10-25 用户连点了两次发送，界面上出现两条一模一样的
  // 消息、后端跑了两轮 —— 而第二次那一轮在「正在想…」里根本看不出来。
  // 按钮的 disabled 只是视觉上的，键盘回车与连点都绕得过它。
  if (sending.value) return

  const text = draft.value.trim()
  const attached = image.value
  if ((text === '' && attached === null) || sending.value) return

  // 先把用户这句放上屏（后端也会存一条），再等回答。
  // 不做乐观更新的坏处是空白等两秒，不知道有没有发出去。
  messages.value.push({
    role: 'user',
    content: attached === null ? text : (text === '' ? '（图片）' : `${text}（图片）`),
    tool_calls: null,
    created_at: new Date().toISOString(),
  })
  draft.value = ''
  image.value = null
  sending.value = true
  error.value = null
  undoNote.value = null
  await scrollToBottom()

  try {
    const r = await chat(text, attached ?? undefined)
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

/** 选了一张图：立刻压好并预览。压失败（不是图 / 太大）就把原因说出来。 */
async function pickImage(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''   // 清掉，否则选同一张图不会再触发 change
  if (!file) return
  error.value = null
  try {
    image.value = await toImageDataUrl(file)
  } catch (err) {
    error.value = reason(err)
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

      <!-- 选好的图片先给个预览：发出去之前用户要能确认是不是这张 -->
      <div v-if="image !== null" class="preview">
        <img :src="image" alt="待发送的图片" />
        <button class="drop" @click="image = null">移除</button>
      </div>

      <div class="inbox">
        <input
          ref="fileInput"
          type="file"
          accept="image/*"
          class="file"
          @change="pickImage"
        />
        <button class="pick" title="选一张照片" @click="fileInput?.click()">拍照</button>
        <input
          v-model="draft"
          type="text"
          :disabled="!configured"
          placeholder="说一句话…"
          @keyup.enter="send"
        />
        <button class="primary" :disabled="!canSend || sending" @click="send">发送</button>
        <button class="undo" title="回退智能体最近一次改动" @click="doUndo">撤销上一步</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.page {
  margin-inline: auto; padding: var(--gap-xl); max-width: 960px; display: flex; flex-direction: column; height: 100%; }
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
.pick { white-space: nowrap; }
.file { display: none; }

.preview {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
  padding: var(--gap-sm) 0;
}
.preview img {
  max-height: 96px;
  max-width: 160px;
  border-radius: var(--radius-sm);
  border: var(--line-width) solid var(--line);
}
.drop { font-size: 11px; color: var(--text-dim); }

.err { color: var(--danger); font-size: 12px; margin: var(--gap-sm) 0 0; }
.ok { color: var(--ok); font-size: 12px; margin: var(--gap-sm) 0 0; }
</style>
