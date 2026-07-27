<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
</script>

<template>
  <div class="rs-app">
    <AppHeader />
    <main class="rs-main">
      <RouterView v-slot="{ Component }">
        <Transition name="rs-fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
    <footer class="rs-footer">
      <span>repo-scout · 浏览器仅访问同源 /api，内部密钥由服务端代理注入</span>
    </footer>
  </div>
</template>

<style scoped>
.rs-app {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.rs-main {
  flex: 1;
  width: 100%;
  max-width: var(--rs-layout-max);
  margin: 0 auto;
  padding: var(--rs-space-8) var(--rs-space-6) var(--rs-space-10);
}

.rs-footer {
  padding: var(--rs-space-5) var(--rs-space-6);
  border-top: 1px solid var(--rs-line);
  background: var(--rs-surface);
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
  text-align: center;
}

@media (max-width: 720px) {
  .rs-main {
    padding: var(--rs-space-5) var(--rs-space-4) var(--rs-space-8);
  }
}
</style>
