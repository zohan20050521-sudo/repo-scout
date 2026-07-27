<script setup lang="ts">
import { computed, ref } from 'vue'
import { Position } from '@element-plus/icons-vue'

const props = defineProps<{ sending: boolean; maxLength?: number }>()
const emit = defineEmits<{ submit: [text: string] }>()

const MAX_LENGTH = props.maxLength ?? 4000
/** 接近上限提示阈值：留 200 字余量 */
const NEAR_LIMIT = MAX_LENGTH - 200

const draft = ref('')

const length = computed(() => draft.value.length)
const nearLimit = computed(() => length.value >= NEAR_LIMIT)
const canSend = computed(() => draft.value.trim().length > 0 && !props.sending)

function submit(): void {
  if (!canSend.value) return
  emit('submit', draft.value)
  draft.value = ''
}

/** Enter 发送，Shift+Enter 换行 */
function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}
</script>

<template>
  <div class="rs-composer">
    <el-input
      v-model="draft"
      type="textarea"
      :rows="3"
      :maxlength="MAX_LENGTH"
      resize="none"
      :disabled="sending"
      placeholder="问点具体的，例如：这个项目怎么在本地跑起来？核心模块是怎么分层的？"
      aria-label="向 Agent 提问"
      @keydown="onKeydown"
    />

    <div class="rs-composer__bar">
      <span class="rs-composer__hint" :class="{ 'is-warn': nearLimit }">
        <template v-if="nearLimit">
          已用 {{ length }} / {{ MAX_LENGTH }} 字，接近单条上限
        </template>
        <template v-else> Enter 发送，Shift + Enter 换行 </template>
      </span>
      <el-button
        type="primary"
        :icon="Position"
        :loading="sending"
        :disabled="!canSend"
        @click="submit"
      >
        {{ sending ? '等待回答' : '发送' }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.rs-composer {
  padding: var(--rs-space-4);
  border-top: 1px solid var(--rs-line);
  background: var(--rs-surface);
}

.rs-composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--rs-space-3);
  margin-top: var(--rs-space-3);
}

.rs-composer__hint {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-composer__hint.is-warn {
  color: var(--rs-warn-500);
}
</style>
