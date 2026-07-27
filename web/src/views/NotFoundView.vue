<script setup lang="ts">
import { Back } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <section class="rs-notfound rs-card">
    <p class="rs-notfound__code rs-mono">404</p>
    <h1 class="rs-notfound__title">这个地址没有对应页面</h1>
    <p class="rs-notfound__desc">
      可能是链接过期或仓库 id 不合法。回到首页重新接入或选择一个已接入的仓库。
    </p>
    <el-button type="primary" :icon="Back" @click="router.push('/')">返回接入页</el-button>
  </section>
</template>

<style scoped>
.rs-notfound {
  padding: var(--rs-space-12) var(--rs-space-8);
  text-align: center;
}

.rs-notfound__code {
  color: var(--rs-ink-300);
  font-size: var(--rs-text-2xl);
  letter-spacing: 0.1em;
}

.rs-notfound__title {
  margin-top: var(--rs-space-3);
  font-size: var(--rs-text-xl);
}

.rs-notfound__desc {
  max-width: 420px;
  margin: var(--rs-space-3) auto var(--rs-space-6);
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
}
</style>
