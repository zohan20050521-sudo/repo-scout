<script setup lang="ts">
import { computed, ref } from 'vue'
import { Document, MagicStick } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useReportStore } from '@/stores/report'
import { useClipboard } from '@/composables/useClipboard'
import { downloadTextFile, reportFileName } from '@/composables/useDownload'
import { extractHeadings } from '@/composables/useMarkdown'
import MarkdownView from './MarkdownView.vue'
import ErrorPanel from './ErrorPanel.vue'
import EmptyState from './EmptyState.vue'
import ReportToc from './ReportToc.vue'
import ReportMetaBar from './ReportMetaBar.vue'
import ReportLoading from './ReportLoading.vue'
import type { RepoSummary } from '@/types/api'

const props = defineProps<{ repo: RepoSummary; indexed: boolean }>()
const reportStore = useReportStore()
const { copy, lastError: clipboardError } = useClipboard()
const copyFailed = ref<string | null>(null)
const report = computed(() => reportStore.report)
const headings = computed(() => (report.value ? extractHeadings(report.value.report) : []))

async function handleGenerate(): Promise<void> {
  copyFailed.value = null
  await reportStore.generate(props.repo.id)
}
function scrollToAnchor(anchor: string): void {
  const el = document.getElementById(anchor)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
async function handleCopy(): Promise<void> {
  if (!report.value) return
  const success = await copy(report.value.report)
  if (success) {
    ElMessage.success('已复制到剪贴板')
    copyFailed.value = null
  } else {
    copyFailed.value = clipboardError.value || '复制失败，请手动选择文本复制'
  }
}
function handleDownload(): void {
  if (!report.value) return
  const filename = reportFileName(props.repo.owner, props.repo.name)
  downloadTextFile(filename, report.value.report)
  ElMessage.success(`已下载 ${filename}`)
}
</script>

<template>
  <section class="rs-report">
    <header class="rs-report__head">
      <h2 class="rs-report__title">
        <el-icon><Document /></el-icon>
        仓库导读报告
      </h2>
      <p class="rs-report__desc">
        五个固定小节：项目定位、技术栈、目录结构解读、上手指引、近期动向。
      </p>
      <button v-if="!report && !reportStore.generating" type="button" class="rs-report__generate" @click="handleGenerate">
        <el-icon><MagicStick /></el-icon>
        生成报告
      </button>
    </header>
    <EmptyState
      v-if="!report && !reportStore.generating && !reportStore.lastError"
      icon="Document"
      title="还没有生成报告"
      :description="
        indexed
          ? '报告为一次性同步生成，只保留在当前浏览器会话；生成后可以复制或下载 Markdown。'
          : '报告为一次性同步生成，只保留在当前浏览器会话；生成后可以复制或下载 Markdown。先索引通常能获得更完整的文档摘录，但可以直接生成。'
      "
    />
    <ReportLoading v-if="reportStore.generating" />
    <ErrorPanel
      v-if="reportStore.lastError"
      title="报告生成失败"
      :error="reportStore.lastError"
      retryable
      retry-text="重新生成"
      @retry="handleGenerate"
    />
    <div v-if="copyFailed" class="rs-report__copy-error">{{ copyFailed }}</div>
    <article v-if="report" class="rs-report__content">
      <header class="rs-report__meta-head">
        <h3 class="rs-report__meta-title">{{ repo.owner }}/{{ repo.name }} 仓库导读</h3>
        <ReportMetaBar :result="report" @copy="handleCopy" @download="handleDownload" />
      </header>
      <div class="rs-report__body">
        <ReportToc :headings="headings" @scroll-to="scrollToAnchor" />
        <div class="rs-report__markdown">
          <MarkdownView :source="report.report" :anchors="true" />
        </div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.rs-report {
  padding: var(--rs-space-6) var(--rs-space-5);
}
.rs-report__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--rs-space-3);
  align-items: center;
  margin-bottom: var(--rs-space-6);
}
.rs-report__title {
  display: flex;
  gap: var(--rs-space-2);
  align-items: center;
  margin: 0;
  font-size: var(--rs-text-xl);
  font-weight: 600;
  color: var(--rs-ink-900);
}
.rs-report__desc {
  flex: 1 1 100%;
  margin: 0;
  font-size: var(--rs-text-sm);
  color: var(--rs-ink-500);
}

.rs-report__generate {
  display: inline-flex;
  gap: var(--rs-space-2);
  align-items: center;
  padding: var(--rs-space-3) var(--rs-space-4);
  border: none;
  border-radius: var(--rs-radius-md);
  background: var(--rs-brand-600);
  color: white;
  cursor: pointer;
  font: inherit;
  font-size: var(--rs-text-base);
  font-weight: 500;
  transition: background 0.2s;
}
.rs-report__generate:hover:not(:disabled) {
  background: var(--rs-brand-700);
}
.rs-report__generate:disabled {
  background: var(--rs-ink-200);
  color: var(--rs-ink-400);
  cursor: not-allowed;
}
.rs-report__copy-error {
  padding: var(--rs-space-3) var(--rs-space-4);
  margin-bottom: var(--rs-space-4);
  border-left: 3px solid var(--rs-warn-500);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-warn-50);
  font-size: var(--rs-text-sm);
  color: var(--rs-warn-700);
}
.rs-report__content {
  margin-top: var(--rs-space-6);
}
.rs-report__meta-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--rs-space-3);
  align-items: center;
  padding-bottom: var(--rs-space-4);
  margin-bottom: var(--rs-space-5);
  border-bottom: 1px solid var(--rs-line);
}
.rs-report__meta-title {
  margin: 0;
  font-size: var(--rs-text-lg);
  font-weight: 600;
  color: var(--rs-ink-900);
}
.rs-report__body {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: var(--rs-space-6);
}
@media (max-width: 900px) {
  .rs-report__body {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .rs-report {
    padding: var(--rs-space-5) var(--rs-space-4);
  }
  .rs-report__head {
    flex-direction: column;
  }
}
</style>
