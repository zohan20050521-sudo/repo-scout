<script setup lang="ts">
import { Link, Right } from '@element-plus/icons-vue'
import { formatRelative } from '@/composables/useFormat'
import type { RepoSummary } from '@/types/api'

defineProps<{ repo: RepoSummary }>()
const emit = defineEmits<{ open: [repo: RepoSummary] }>()
</script>

<template>
  <article class="rs-repo-card">
    <button type="button" class="rs-repo-card__main" @click="emit('open', repo)">
      <span class="rs-repo-card__name rs-mono">{{ repo.owner }}/{{ repo.name }}</span>
      <span class="rs-repo-card__desc">{{ repo.description || '该仓库没有填写描述' }}</span>
      <span class="rs-repo-card__meta">
        <el-tag size="small" type="info" effect="plain">{{ repo.defaultBranch }}</el-tag>
        <span class="rs-muted">更新于 {{ formatRelative(repo.updatedAt) }}</span>
      </span>
      <el-icon class="rs-repo-card__arrow"><Right /></el-icon>
    </button>
    <a
      class="rs-repo-card__link"
      :href="repo.htmlUrl"
      target="_blank"
      rel="noopener noreferrer"
      :aria-label="`在 GitHub 打开 ${repo.owner}/${repo.name}`"
    >
      <el-icon><Link /></el-icon>
      GitHub
    </a>
  </article>
</template>

<style scoped>
.rs-repo-card {
  position: relative;
  display: flex;
  align-items: stretch;
  border: 1px solid var(--rs-line);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface);
  transition:
    border-color var(--rs-duration-fast) var(--rs-ease),
    box-shadow var(--rs-duration-fast) var(--rs-ease);
}

.rs-repo-card:hover {
  border-color: var(--rs-brand-300);
  box-shadow: var(--rs-shadow-sm);
}

.rs-repo-card__main {
  display: grid;
  flex: 1;
  gap: var(--rs-space-1);
  padding: var(--rs-space-4);
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
  font: inherit;
  min-width: 0;
}

.rs-repo-card__name {
  color: var(--rs-ink-900);
  font-size: var(--rs-text-base);
  font-weight: 600;
}

.rs-repo-card__desc {
  overflow: hidden;
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-repo-card__meta {
  display: flex;
  align-items: center;
  gap: var(--rs-space-3);
  margin-top: var(--rs-space-1);
  font-size: var(--rs-text-xs);
}

.rs-repo-card__arrow {
  position: absolute;
  top: var(--rs-space-4);
  right: var(--rs-space-4);
  color: var(--rs-ink-300);
}

.rs-repo-card__link {
  display: flex;
  align-items: center;
  gap: 4px;
  align-self: flex-end;
  padding: var(--rs-space-2) var(--rs-space-4);
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-repo-card__link:hover {
  color: var(--rs-brand-600);
  text-decoration: none;
}
</style>
