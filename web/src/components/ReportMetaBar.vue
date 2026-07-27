<script setup lang="ts">
import { DocumentCopy, Download } from '@element-plus/icons-vue'
import type { ReportResult } from '@/types/api'
import { formatCost, formatDateTime } from '@/composables/useFormat'

defineProps<{ result: ReportResult }>()
const emit = defineEmits<{ copy: []; download: [] }>()
</script>

<template>
  <div class="rs-report-meta">
    <span class="rs-report-meta__item">生成于 {{ formatDateTime(result.generatedAt) }}</span>
    <span class="rs-report-meta__item">耗时 {{ formatCost(result.costMs) }}</span>
    <button type="button" class="rs-report-meta__action" @click="emit('copy')">
      <el-icon><DocumentCopy /></el-icon>
      复制 Markdown
    </button>
    <button type="button" class="rs-report-meta__action" @click="emit('download')">
      <el-icon><Download /></el-icon>
      下载 .md
    </button>
  </div>
</template>

<style scoped>
.rs-report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--rs-space-3);
  align-items: center;
  margin-left: auto;
  font-size: var(--rs-text-sm);
  color: var(--rs-ink-400);
}

.rs-report-meta__item {
  padding: var(--rs-space-1) var(--rs-space-2);
}

.rs-report-meta__action {
  display: inline-flex;
  gap: var(--rs-space-1);
  align-items: center;
  padding: var(--rs-space-2) var(--rs-space-3);
  border: 1px solid var(--rs-line);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-surface);
  color: var(--rs-brand-600);
  cursor: pointer;
  font: inherit;
  font-size: var(--rs-text-sm);
  transition: all 0.2s;
}

.rs-report-meta__action:hover {
  border-color: var(--rs-brand-300);
  background: var(--rs-brand-50);
}

@media (max-width: 720px) {
  .rs-report-meta {
    margin-left: 0;
  }
}
</style>
