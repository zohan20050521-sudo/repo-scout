<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useRepoStore } from '@/stores/repo'

const route = useRoute()
const repoStore = useRepoStore()

const inWorkspace = computed(() => route.name === 'repo-workspace')
const currentName = computed(() => repoStore.repoFullName)
</script>

<template>
  <header class="rs-header">
    <div class="rs-header__inner">
      <RouterLink to="/" class="rs-brand">
        <span class="rs-brand__mark" aria-hidden="true">rs</span>
        <span class="rs-brand__text">
          <strong>repo-scout</strong>
          <small>让陌生 GitHub 仓库快速变得可读</small>
        </span>
      </RouterLink>

      <div v-if="inWorkspace && currentName" class="rs-header__context">
        <span class="rs-header__label">当前仓库</span>
        <span class="rs-mono rs-header__repo">{{ currentName }}</span>
      </div>

      <RouterLink v-if="inWorkspace" to="/" class="rs-header__back">切换仓库</RouterLink>
    </div>
  </header>
</template>

<style scoped>
.rs-header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgb(255 255 255 / 92%);
  border-bottom: 1px solid var(--rs-line);
  backdrop-filter: blur(8px);
}

.rs-header__inner {
  display: flex;
  align-items: center;
  gap: var(--rs-space-4);
  height: var(--rs-header-height);
  max-width: var(--rs-layout-max);
  margin: 0 auto;
  padding: 0 var(--rs-space-6);
}

.rs-brand {
  display: flex;
  align-items: center;
  gap: var(--rs-space-3);
  color: inherit;
  text-decoration: none;
}

.rs-brand:hover {
  text-decoration: none;
}

.rs-brand__mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--rs-radius-sm);
  background: var(--rs-brand-600);
  color: #fff;
  font-family: var(--rs-font-mono);
  font-size: var(--rs-text-sm);
  font-weight: 600;
}

.rs-brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.rs-brand__text strong {
  color: var(--rs-ink-900);
  font-size: var(--rs-text-md);
  letter-spacing: -0.01em;
}

.rs-brand__text small {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-header__context {
  display: flex;
  align-items: baseline;
  gap: var(--rs-space-2);
  margin-left: auto;
  min-width: 0;
}

.rs-header__label {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-header__repo {
  overflow: hidden;
  color: var(--rs-ink-800);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-header__back {
  flex-shrink: 0;
  padding: 5px var(--rs-space-3);
  border: 1px solid var(--rs-line-strong);
  border-radius: var(--rs-radius-pill);
  color: var(--rs-ink-700);
  font-size: var(--rs-text-xs);
}

.rs-header__back:hover {
  border-color: var(--rs-brand-300);
  background: var(--rs-brand-50);
  color: var(--rs-brand-700);
  text-decoration: none;
}

@media (max-width: 720px) {
  .rs-header__inner {
    padding: 0 var(--rs-space-4);
  }

  .rs-brand__text small,
  .rs-header__label {
    display: none;
  }

  .rs-header__repo {
    max-width: 140px;
    font-size: var(--rs-text-xs);
  }
}
</style>
