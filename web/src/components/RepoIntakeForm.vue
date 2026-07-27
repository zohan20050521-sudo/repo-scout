<script setup lang="ts">
import { computed, ref } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import { createRepo } from '@/api/repos'
import type { ApiError} from '@/api/error';
import { toApiError } from '@/api/error'
import type { RepoSummary } from '@/types/api'
import ErrorPanel from './ErrorPanel.vue'

const emit = defineEmits<{ connected: [repo: RepoSummary] }>()

const input = ref('')
const submitting = ref(false)
const error = ref<ApiError | null>(null)

/** 只做基础非空校验，最终格式规则以服务端为准 */
const canSubmit = computed(() => input.value.trim().length > 0 && !submitting.value)

const examples = ['spring-projects/spring-petclinic', 'https://github.com/vuejs/core']

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = null
  try {
    const repo = await createRepo(input.value.trim())
    emit('connected', repo)
  } catch (caught) {
    error.value = toApiError(caught)
  } finally {
    submitting.value = false
  }
}

function useExample(value: string): void {
  input.value = value
}
</script>

<template>
  <section class="rs-intake rs-card">
    <h1 class="rs-intake__title">把一个陌生仓库读懂，从这里开始</h1>
    <p class="rs-intake__desc">
      填入公开 GitHub 仓库，接入后可以建立文档索引、围绕它多轮提问，并一键生成五节导读报告。
    </p>

    <form class="rs-intake__form" @submit.prevent="submit">
      <el-input
        v-model="input"
        class="rs-intake__input"
        size="large"
        placeholder="owner/repo 或 https://github.com/owner/repo"
        :disabled="submitting"
        clearable
        aria-label="GitHub 仓库地址"
        @keydown.enter.prevent="submit"
      />
      <el-button
        type="primary"
        size="large"
        :icon="Promotion"
        :loading="submitting"
        :disabled="!canSubmit"
        native-type="submit"
      >
        {{ submitting ? '接入中' : '接入仓库' }}
      </el-button>
    </form>

    <p class="rs-intake__tip">
      <span class="rs-muted">仅支持公开仓库。试试</span>
      <button
        v-for="item in examples"
        :key="item"
        type="button"
        class="rs-intake__example rs-mono"
        :disabled="submitting"
        @click="useExample(item)"
      >
        {{ item }}
      </button>
    </p>

    <ErrorPanel
      v-if="error"
      class="rs-intake__error"
      title="仓库接入失败"
      :error="error"
      retryable
      retry-text="重新接入"
      @retry="submit"
    />
  </section>
</template>

<style scoped>
.rs-intake {
  padding: var(--rs-space-10) var(--rs-space-8);
}

.rs-intake__title {
  font-size: var(--rs-text-2xl);
  letter-spacing: -0.02em;
}

.rs-intake__desc {
  max-width: 620px;
  margin-top: var(--rs-space-3);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-md);
}

.rs-intake__form {
  display: flex;
  gap: var(--rs-space-3);
  margin-top: var(--rs-space-6);
}

.rs-intake__input {
  max-width: 560px;
}

.rs-intake__tip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--rs-space-2);
  margin-top: var(--rs-space-4);
  font-size: var(--rs-text-sm);
}

.rs-intake__example {
  padding: 2px var(--rs-space-2);
  border: 1px solid var(--rs-line);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-surface-muted);
  color: var(--rs-ink-600, var(--rs-ink-700));
  cursor: pointer;
  font-size: var(--rs-text-xs);
  transition: all var(--rs-duration-fast) var(--rs-ease);
}

.rs-intake__example:hover:not(:disabled) {
  border-color: var(--rs-brand-300);
  background: var(--rs-brand-50);
  color: var(--rs-brand-700);
}

.rs-intake__error {
  margin-top: var(--rs-space-5);
}

@media (max-width: 720px) {
  .rs-intake {
    padding: var(--rs-space-6) var(--rs-space-5);
  }

  .rs-intake__title {
    font-size: var(--rs-text-xl);
  }

  .rs-intake__form {
    flex-direction: column;
  }

  .rs-intake__input {
    max-width: none;
  }
}
</style>
