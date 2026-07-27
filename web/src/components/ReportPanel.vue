<script setup lang="ts">
import { computed, ref } from 'vue'
import { Document, Download, DocumentCopy, Loading, MagicStick } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useReportStore } from '@/stores/report'
import { useClipboard } from '@/composables/useClipboard'
import { downloadTextFile, reportFileName } from '@/composables/useDownload'
import { extractHeadings } from '@/composables/useMarkdown'
import { formatCost, formatDateTime } from '@/composables/useFormat'
import MarkdownView from './MarkdownView.vue'
import ErrorPanel from './ErrorPanel.vue'
import EmptyState from './EmptyState.vue'
import type { RepoSummary } from '@/types/api'

const props = defineProps<{ repo: RepoSummary; indexed: boolean }>()

const reportStore = useReportStore()
const { copy, lastError: clipboardError } = useClipboard()
const copyFailed = ref<string | null>(null)

const report = computed(() => reportStore.report)
const headings = computed(() => (report.value ? extractHeadings(report.value.report) : []))

async function generate(): Promise<void> {
  const result = await reportStore.generate(props.repo.id)
  if (result) ElMessage.success('报告已生成')
}

async function onCopy(): Promise<void> {
  if (!report.value) return
  copyFailed.value = null
  const ok = await copy(report.value.report)
  if (ok) {
    ElMessage.success('Markdown 已复制到剪贴板')
    return
  }
  copyFailed.value = clipboardError.value ?? '复制失败，请手动选中复制'
  ElMessage.error(copyFailed.value)
}

function onDownload(): void {
  if (!report.value) return
  downloadTextFile(reportFileName(props.repo.owner, props.repo.name), report.value.report)
}

function scrollToAnchor(anchor: string): void {
  document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <section class="rs-report rs-card">
    <header class="rs-report__head">
      <div>
        <h2 class="rs-section-title">仓库导读报告</h2>
        <p class="rs-report__sub">
          五个固定小节：项目定位、技术栈、目录结构解读、上手指引、近期动向。
        </p>
      </div>
      <el-button
        type="primary"
        :icon="MagicStick"
        :loading="reportStore.generating"
        :disabled="reportStore.generating"
        @click="generate"
      >
        {{ report ? '重新生成' : '生成报告' }}
      </el-button>
    </header>

    <el-alert
      v-if="!indexed"
      class="rs-report__notice"
      type="info"
      :closable="false"
      show-icon
      title="先索引通常能获得更完整的文档摘录"
      description="未索引也可以直接生成，报告会在摘录区标注未索引。"
    />

    <div v-if="reportStore.generating" class="rs-report__waiting" role="status">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>
        正在确定性取数并由模型生成五节报告，通常需要约 20–30 秒，请不要关闭页面。
      </span>
    </div>

    <ErrorPanel
      v-if="reportStore.lastError"
      class="rs-report__error"
      title="报告生成失败"
      :error="reportStore.lastError"
      retryable
      retry-text="重新生成"
      @retry="generate"
    />

    <EmptyState
      v-if="!report && !reportStore.generating"
      title="还没有生成报告"
      description="报告为一次性同步生成，只保留在当前浏览器会话；生成后可以复制或下载 Markdown。"
    >
      <template #icon><el-icon><Document /></el-icon></template>
    </EmptyState>

    <template v-if="report">
      <div class="rs-report__meta">
        <span>生成时间 {{ formatDateTime(report.generatedAt) }}</span>
        <span>耗时 {{ formatCost(report.costMs) }}</span>
        <span class="rs-report__actions">
          <el-button size="small" :icon="DocumentCopy" @click="onCopy">复制 Markdown</el-button>
          <el-button size="small" :icon="Download" @click="onDownload">下载 .md</el-button>
        </span>
      </div>

      <p v-if="copyFailed" class="rs-report__copy-error" role="alert">{{ copyFailed }}</p>

      <div class="rs-report__body">
        <nav v-if="headings.length" class="rs-report__toc" aria-label="报告小节">
          <button
            v-for="heading in headings"
            :key="heading.anchor"
            type="button"
            class="rs-report__toc-item"
            @click="scrollToAnchor(heading.anchor)"
          >
            {{ heading.text }}
          </button>
        </nav>
        <MarkdownView class="rs-report__markdown" :source="report.report" anchors />
      </div>
    </template>
  </section>
</template>

<style scoped>
.rs-report {
  padding: var(--rs-space-6);
}

.rs-report__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--rs-space-4);
}

.rs-report__sub {
  margin-top: var(--rs-space-1);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
}

.rs-report__notice,
.rs-report__error {
  margin-top: var(--rs-space-4);
}

.rs-report__waiting {
  display: flex;
  align-items: center;
  gap: var(--rs-space-2);
  margin-top: var(--rs-space-4);
  padding: var(--rs-space-3) var(--rs-space-4);
  border: 1px solid #f0e0c8;
  border-radius: var(--rs-radius-md);
  background: var(--rs-warn-50);
  color: var(--rs-warn-500);
  font-size: var(--rs-text-sm);
}

.rs-report__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--rs-space-4);
  margin-top: var(--rs-space-5);
  padding: var(--rs-space-3) var(--rs-space-4);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface-muted);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-xs);
}

.rs-report__actions {
  display: flex;
  gap: var(--rs-space-2);
  margin-left: auto;
}

.rs-report__copy-error {
  margin-top: var(--rs-space-2);
  color: var(--rs-danger-500);
  font-size: var(--rs-text-sm);
}

.rs-report__body {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: var(--rs-space-6);
  margin-top: var(--rs-space-5);
}

.rs-report__toc {
  display: flex;
  position: sticky;
  top: calc(var(--rs-header-height) + var(--rs-space-4));
  flex-direction: column;
  align-self: start;
  gap: 2px;
  padding-left: var(--rs-space-2);
  border-left: 2px solid var(--rs-line);
}

.rs-report__toc-item {
  padding: var(--rs-space-1) var(--rs-space-2);
  border: none;
  border-radius: var(--rs-radius-sm);
  background: none;
  color: var(--rs-ink-500);
  cursor: pointer;
  font: inherit;
  font-size: var(--rs-text-xs);
  text-align: left;
}

.rs-report__toc-item:hover {
  background: var(--rs-brand-50);
  color: var(--rs-brand-700);
}

@media (max-width: 900px) {
  .rs-report__body {
    grid-template-columns: 1fr;
  }

  .rs-report__toc {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
    padding-left: 0;
    border-left: none;
  }

  .rs-report__toc-item {
    border: 1px solid var(--rs-line);
  }
}

@media (max-width: 720px) {
  .rs-report {
    padding: var(--rs-space-5) var(--rs-space-4);
  }

  .rs-report__head {
    flex-direction: column;
  }

  .rs-report__actions {
    margin-left: 0;
  }
}
</style>
