<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue'
</script>

<template>
  <div class="rs-report-loading">
    <el-icon class="rs-report-loading__icon"><Loading /></el-icon>
    <p class="rs-report-loading__text">
      正在确定性取数并由模型生成五节报告，通常需要约 20–30 秒，请不要关闭页面。
    </p>
  </div>
</template>

<style scoped>
.rs-report-loading {
  display: flex;
  flex-direction: column;
  gap: var(--rs-space-4);
  align-items: center;
  padding: var(--rs-space-8) var(--rs-space-4);
}

.rs-report-loading__icon {
  font-size: 2rem;
  color: var(--rs-brand-600);
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.rs-report-loading__text {
  max-width: 480px;
  margin: 0;
  font-size: var(--rs-text-sm);
  text-align: center;
  color: var(--rs-ink-500);
}
</style>
