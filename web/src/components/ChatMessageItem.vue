<script setup lang="ts">
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import MarkdownView from './MarkdownView.vue'
import CitationCard from './CitationCard.vue'
import type { ChatMessage } from '@/types/chat'

const props = defineProps<{ message: ChatMessage; retrying: boolean }>()
const emit = defineEmits<{ retry: [id: string] }>()

const isUser = computed(() => props.message.role === 'user')
/** citations 按轮独立展示；为空时不造「已引用」标签 */
const citations = computed(() => props.message.citations ?? [])
</script>

<template>
  <div class="rs-msg" :class="isUser ? 'rs-msg--user' : 'rs-msg--assistant'">
    <div class="rs-msg__role">{{ isUser ? '你' : 'Agent' }}</div>

    <div class="rs-msg__body">
      <p v-if="isUser" class="rs-msg__text">{{ message.content }}</p>
      <MarkdownView v-else :source="message.content" />

      <div v-if="!isUser && citations.length" class="rs-msg__citations">
        <p class="rs-msg__citations-title">
          本轮引用 {{ citations.length }} 处仓库文档
        </p>
        <CitationCard
          v-for="(citation, index) in citations"
          :key="`${citation.filePath}-${citation.chunkIndex}-${index}`"
          :citation="citation"
        />
      </div>

      <div v-if="message.failed" class="rs-msg__failed">
        <span>{{ message.errorMessage ?? '这条消息没能发送成功' }}</span>
        <el-button
          size="small"
          :icon="Refresh"
          :loading="retrying"
          :disabled="retrying"
          @click="emit('retry', message.id)"
        >
          重试这条
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rs-msg {
  display: grid;
  gap: var(--rs-space-2);
}

.rs-msg__role {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.rs-msg--user .rs-msg__body {
  padding: var(--rs-space-3) var(--rs-space-4);
  border: 1px solid var(--rs-brand-100);
  border-radius: var(--rs-radius-md);
  background: var(--rs-brand-50);
}

.rs-msg--assistant .rs-msg__body {
  padding: var(--rs-space-4) 0 0;
  border-top: 1px solid var(--rs-line);
}

.rs-msg__text {
  color: var(--rs-ink-800);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.rs-msg__citations {
  display: grid;
  gap: var(--rs-space-2);
  margin-top: var(--rs-space-4);
  padding: var(--rs-space-4);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface-muted);
}

.rs-msg__citations-title {
  color: var(--rs-ink-500);
  font-size: var(--rs-text-xs);
  font-weight: 600;
}

.rs-msg__failed {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--rs-space-3);
  margin-top: var(--rs-space-2);
  padding: var(--rs-space-2) var(--rs-space-3);
  border: 1px solid #f0d7d5;
  border-radius: var(--rs-radius-sm);
  background: var(--rs-danger-50);
  color: var(--rs-danger-500);
  font-size: var(--rs-text-sm);
}
</style>
