<script setup lang="ts">
import { computed } from 'vue'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import type { ApiError} from '@/api/error';
import { errorHint } from '@/api/error'

const props = defineProps<{
  error: ApiError | null
  title?: string
  retryText?: string
  /** 是否显示重试按钮 */
  retryable?: boolean
}>()

const emit = defineEmits<{ retry: [] }>()

const hint = computed(() => (props.error ? errorHint(props.error) : ''))
const statusLabel = computed(() => {
  if (!props.error) return ''
  const parts: string[] = [props.error.code]
  if (props.error.status !== null) parts.push(`HTTP ${props.error.status}`)
  return parts.join(' · ')
})
</script>

<template>
  <div v-if="error" class="rs-error" role="alert">
    <el-icon class="rs-error__icon"><WarningFilled /></el-icon>
    <div class="rs-error__body">
      <p class="rs-error__title">{{ title ?? '这一步没能完成' }}</p>
      <p class="rs-error__message">{{ error.message }}</p>
      <p v-if="hint" class="rs-error__hint">{{ hint }}</p>
      <p class="rs-error__code rs-mono">{{ statusLabel }}</p>
    </div>
    <el-button
      v-if="retryable"
      class="rs-error__action"
      size="small"
      :icon="Refresh"
      @click="emit('retry')"
    >
      {{ retryText ?? '重试' }}
    </el-button>
  </div>
</template>

<style scoped>
.rs-error {
  display: flex;
  align-items: flex-start;
  gap: var(--rs-space-3);
  padding: var(--rs-space-4);
  border: 1px solid #f0d7d5;
  border-radius: var(--rs-radius-md);
  background: var(--rs-danger-50);
}

.rs-error__icon {
  margin-top: 2px;
  color: var(--rs-danger-500);
  font-size: 18px;
}

.rs-error__body {
  flex: 1;
  min-width: 0;
}

.rs-error__title {
  color: var(--rs-ink-900);
  font-weight: 600;
}

.rs-error__message {
  margin-top: 2px;
  color: var(--rs-ink-700);
  overflow-wrap: break-word;
}

.rs-error__hint {
  margin-top: var(--rs-space-1);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
}

.rs-error__code {
  margin-top: var(--rs-space-2);
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-error__action {
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .rs-error {
    flex-wrap: wrap;
  }

  .rs-error__action {
    margin-left: 30px;
  }
}
</style>
