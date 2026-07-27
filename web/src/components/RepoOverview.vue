<script setup lang="ts">
import { Link } from '@element-plus/icons-vue'
import { formatDateTime } from '@/composables/useFormat'
import type { RepoSummary } from '@/types/api'

defineProps<{ repo: RepoSummary }>()
</script>

<template>
  <section class="rs-overview rs-card">
    <header class="rs-overview__head">
      <div class="rs-overview__title">
        <h1 class="rs-mono rs-overview__name">{{ repo.owner }}/{{ repo.name }}</h1>
        <el-tag size="small" type="info" effect="plain">
          默认分支 {{ repo.defaultBranch }}
        </el-tag>
      </div>
      <a class="rs-overview__link" :href="repo.htmlUrl" target="_blank" rel="noopener noreferrer">
        <el-icon><Link /></el-icon>
        在 GitHub 查看
      </a>
    </header>

    <p class="rs-overview__desc">{{ repo.description || '该仓库没有填写描述。' }}</p>

    <dl class="rs-overview__facts">
      <div>
        <dt>仓库 id</dt>
        <dd class="rs-mono">{{ repo.id }}</dd>
      </div>
      <div>
        <dt>接入时间</dt>
        <dd>{{ formatDateTime(repo.createdAt) }}</dd>
      </div>
      <div>
        <dt>元信息刷新</dt>
        <dd>{{ formatDateTime(repo.updatedAt) }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.rs-overview {
  padding: var(--rs-space-6);
}

.rs-overview__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--rs-space-4);
}

.rs-overview__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--rs-space-3);
  min-width: 0;
}

.rs-overview__name {
  font-size: var(--rs-text-xl);
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.rs-overview__link {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--rs-text-sm);
}

.rs-overview__desc {
  margin-top: var(--rs-space-3);
  color: var(--rs-ink-600, var(--rs-ink-700));
  font-size: var(--rs-text-md);
}

.rs-overview__facts {
  display: grid;
  gap: var(--rs-space-4);
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  margin: var(--rs-space-5) 0 0;
  padding-top: var(--rs-space-4);
  border-top: 1px solid var(--rs-line);
}

.rs-overview__facts dt {
  color: var(--rs-ink-400);
  font-size: var(--rs-text-xs);
}

.rs-overview__facts dd {
  margin: 2px 0 0;
  color: var(--rs-ink-800);
  font-size: var(--rs-text-sm);
}

@media (max-width: 720px) {
  .rs-overview {
    padding: var(--rs-space-5) var(--rs-space-4);
  }

  .rs-overview__head {
    flex-direction: column;
  }
}
</style>
