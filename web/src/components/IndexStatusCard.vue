<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import { DataAnalysis, Loading, Refresh } from '@element-plus/icons-vue'
import { useRepoStore } from '@/stores/repo'
import { formatCost, formatDateTime } from '@/composables/useFormat'
import ErrorPanel from './ErrorPanel.vue'

const props = defineProps<{ repoId: number }>()
const repoStore = useRepoStore()

const status = computed(() => repoStore.indexStatus)
const indexed = computed(() => status.value?.indexed === true)
const task = computed(() => status.value?.task ?? null)
const result = computed(() => {
  if (task.value?.status === 'SUCCEEDED') return task.value
  return repoStore.lastIndexResult
})
const taskRunning = computed(() => task.value?.status === 'QUEUED' || task.value?.status === 'RUNNING')

const actionText = computed(() => (indexed.value ? '重建索引' : '建立文档索引'))

function runIndex(): void {
  void repoStore.runIndex(props.repoId)
}

onBeforeUnmount(() => repoStore.invalidatePolling())
</script>

<template>
  <section class="rs-index rs-card" aria-labelledby="rs-index-title">
    <header class="rs-index__head">
      <h2 id="rs-index-title" class="rs-section-title">文档索引</h2>
      <el-tag v-if="status" :type="taskRunning ? 'warning' : indexed ? 'success' : 'info'" size="small" effect="light">
        {{ taskRunning ? (task?.status === 'QUEUED' ? '排队中' : '索引进行中') : indexed ? '已索引' : '未索引' }}
      </el-tag>
    </header>

    <el-skeleton v-if="repoStore.indexStatusLoading && !status" :rows="2" animated />

    <ErrorPanel
      v-else-if="repoStore.indexStatusError && !status"
      title="索引状态没能加载"
      :error="repoStore.indexStatusError"
      retryable
      @retry="repoStore.fetchIndexStatus(props.repoId)"
    />

    <template v-else-if="status">
      <dl class="rs-index__facts">
        <div>
          <dt>文档数</dt>
          <dd class="rs-mono">{{ status.fileCount }}</dd>
        </div>
        <div>
          <dt>文档块数</dt>
          <dd class="rs-mono">{{ status.chunkCount }}</dd>
        </div>
        <div>
          <dt>最近索引时间</dt>
          <dd>{{ formatDateTime(status.indexedAt) }}</dd>
        </div>
      </dl>

      <p class="rs-index__explain">
        <template v-if="indexed">
          重建会先删除该仓库旧的文档块再整体重新生成，是幂等操作，块数不会因重复触发而增长。
        </template>
        <template v-else>
          索引会拉取该仓库的 README 与 <code>docs/</code>
          文本文档，切分后生成向量，用于带引用的问答与更完整的报告摘录。
        </template>
      </p>

      <div v-if="repoStore.indexing" class="rs-index__waiting" role="status">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>
          {{ task?.status === 'QUEUED' ? '索引任务已排队，单线程 worker 将按顺序处理。' : '索引进行中，服务端完成后页面会自动更新。' }}
        </span>
      </div>

      <div class="rs-index__actions">
        <el-button
          type="primary"
          :icon="indexed ? Refresh : DataAnalysis"
          :loading="repoStore.indexing"
          :disabled="repoStore.indexing"
          @click="runIndex"
        >
          {{ repoStore.indexing ? (task?.status === 'QUEUED' ? '排队中' : '索引进行中') : actionText }}
        </el-button>
        <el-button
          text
          :disabled="repoStore.indexStatusLoading"
          @click="repoStore.fetchIndexStatus(props.repoId)"
        >
          刷新状态
        </el-button>
      </div>

      <el-alert
        v-if="result && !repoStore.indexing && !repoStore.indexError"
        class="rs-index__result"
        type="success"
        :closable="false"
        show-icon
      >
        本次索引完成：{{ result.fileCount }} 个文档、{{ result.chunkCount }} 个块，耗时
        {{ formatCost(result.costMs ?? 0) }}。
      </el-alert>

      <ErrorPanel
        v-if="repoStore.indexError"
        class="rs-index__error"
        title="索引没能完成"
        :error="repoStore.indexError"
        retryable
        retry-text="重新索引"
        @retry="runIndex"
      />
    </template>
  </section>
</template>

<style scoped>
.rs-index {
  padding: var(--rs-space-6);
}

.rs-index__head {
  display: flex;
  align-items: center;
  gap: var(--rs-space-3);
  margin-bottom: var(--rs-space-4);
}

.rs-index__facts {
  display: grid;
  gap: var(--rs-space-4);
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  margin: 0;
  padding: var(--rs-space-4);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface-muted);
}

.rs-index__facts dt {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-index__facts dd {
  margin: 2px 0 0;
  color: var(--rs-ink-900);
  font-size: var(--rs-text-md);
  font-weight: 600;
}

.rs-index__explain {
  margin-top: var(--rs-space-4);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
}

.rs-index__explain code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--rs-surface-sunken);
  font-size: 0.9em;
}

.rs-index__waiting {
  display: flex;
  align-items: flex-start;
  gap: var(--rs-space-2);
  margin-top: var(--rs-space-4);
  padding: var(--rs-space-3) var(--rs-space-4);
  border: 1px solid #f0e0c8;
  border-radius: var(--rs-radius-md);
  background: var(--rs-warn-50);
  color: var(--rs-warn-500);
  font-size: var(--rs-text-sm);
}

.rs-index__actions {
  display: flex;
  align-items: center;
  gap: var(--rs-space-2);
  margin-top: var(--rs-space-5);
}

.rs-index__result,
.rs-index__error {
  margin-top: var(--rs-space-4);
}

@media (max-width: 720px) {
  .rs-index {
    padding: var(--rs-space-5) var(--rs-space-4);
  }
}
</style>
