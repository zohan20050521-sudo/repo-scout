<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChatLineSquare, Loading, Plus } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { useAutoScroll } from '@/composables/useAutoScroll'
import ChatMessageItem from './ChatMessageItem.vue'
import ChatComposer from './ChatComposer.vue'
import EmptyState from './EmptyState.vue'

const props = defineProps<{ repoId: number; indexed: boolean }>()

const chatStore = useChatStore()
const scrollArea = ref<HTMLElement | null>(null)
const { scrollToBottom } = useAutoScroll(scrollArea)

const sessionLabel = computed(() => chatStore.sessionId ?? '尚未创建')
const canReset = computed(() => chatStore.hasConversation && !chatStore.sending)

watch(
  () => chatStore.messages.length,
  () => {
    void scrollToBottom()
  },
)

watch(
  () => chatStore.sending,
  (sending) => {
    if (sending) void scrollToBottom()
  },
)

async function onSubmit(text: string): Promise<void> {
  await chatStore.send(text, props.repoId)
}

async function onRetry(id: string): Promise<void> {
  await chatStore.retry(id, props.repoId)
}
</script>

<template>
  <section class="rs-chat rs-card">
    <header class="rs-chat__head">
      <div>
        <h2 class="rs-section-title">围绕这个仓库提问</h2>
        <p class="rs-chat__session">
          会话 <span class="rs-mono">{{ sessionLabel }}</span>
          <span class="rs-muted"> · 仅保留在当前浏览器会话</span>
        </p>
      </div>
      <el-button size="small" :icon="Plus" :disabled="!canReset" @click="chatStore.resetConversation()">
        新对话
      </el-button>
    </header>

    <div ref="scrollArea" class="rs-chat__scroll rs-scroll">
      <EmptyState
        v-if="!chatStore.hasConversation"
        title="还没有开始对话"
        :description="
          indexed
            ? '该仓库已建立文档索引，回答会尽量附上文档引用。'
            : '该仓库尚未索引，Agent 会直接调用 GitHub 工具作答；先建立索引通常能拿到带引用的回答。'
        "
      >
        <template #icon><el-icon><ChatLineSquare /></el-icon></template>
      </EmptyState>

      <div v-else class="rs-chat__list">
        <ChatMessageItem
          v-for="message in chatStore.messages"
          :key="message.id"
          :message="message"
          :retrying="chatStore.sending"
          @retry="onRetry"
        />

        <div v-if="chatStore.sending" class="rs-chat__waiting" role="status">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>Agent 正在结合仓库文档与实时信息组织回答，这一步可能要等十几秒。</span>
        </div>
      </div>
    </div>

    <ChatComposer :sending="chatStore.sending" @submit="onSubmit" />
  </section>
</template>

<style scoped>
.rs-chat {
  display: flex;
  flex-direction: column;
  min-height: 520px;
  overflow: hidden;
}

.rs-chat__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--rs-space-3);
  padding: var(--rs-space-5) var(--rs-space-5) var(--rs-space-4);
  border-bottom: 1px solid var(--rs-line);
}

.rs-chat__session {
  margin-top: var(--rs-space-1);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-xs);
  overflow-wrap: anywhere;
}

.rs-chat__scroll {
  flex: 1;
  max-height: 60vh;
  min-height: 260px;
  overflow-y: auto;
  padding: var(--rs-space-5);
  background: var(--rs-surface-muted);
}

.rs-chat__list {
  display: grid;
  gap: var(--rs-space-5);
}

.rs-chat__waiting {
  display: flex;
  align-items: center;
  gap: var(--rs-space-2);
  padding: var(--rs-space-3) var(--rs-space-4);
  border: 1px solid #f0e0c8;
  border-radius: var(--rs-radius-md);
  background: var(--rs-warn-50);
  color: var(--rs-warn-500);
  font-size: var(--rs-text-sm);
}

@media (max-width: 720px) {
  .rs-chat__head,
  .rs-chat__scroll {
    padding: var(--rs-space-4);
  }

  .rs-chat__scroll {
    max-height: none;
  }
}
</style>
