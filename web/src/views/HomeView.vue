<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useRepoStore } from '@/stores/repo'
import type { RepoSummary } from '@/types/api'
import RepoIntakeForm from '@/components/RepoIntakeForm.vue'
import RepoCard from '@/components/RepoCard.vue'
import ErrorPanel from '@/components/ErrorPanel.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const repoStore = useRepoStore()

onMounted(() => {
  void repoStore.fetchRepos()
})

function openRepo(repo: RepoSummary): void {
  void router.push({ name: 'repo-workspace', params: { repoId: repo.id } })
}

function onConnected(repo: RepoSummary): void {
  repoStore.upsertRepo(repo)
  ElMessage.success(`已接入 ${repo.owner}/${repo.name}`)
  openRepo(repo)
}
</script>

<template>
  <div class="rs-home">
    <RepoIntakeForm @connected="onConnected" />

    <section class="rs-recent rs-card">
      <header class="rs-recent__head">
        <h2 class="rs-section-title">最近接入的仓库</h2>
        <span v-if="repoStore.repos.length" class="rs-muted rs-recent__count">
          共 {{ repoStore.repos.length }} 个
        </span>
      </header>

      <div v-if="repoStore.listLoading" class="rs-recent__loading">
        <el-skeleton :rows="3" animated />
      </div>

      <ErrorPanel
        v-else-if="repoStore.listError"
        title="仓库列表没能加载"
        :error="repoStore.listError"
        retryable
        @retry="repoStore.fetchRepos()"
      />

      <EmptyState
        v-else-if="!repoStore.repos.length"
        title="还没有接入任何仓库"
        description="在上面填入一个公开仓库地址，接入后就会出现在这里，方便下次直接进入工作区。"
      />

      <div v-else class="rs-recent__list">
        <RepoCard v-for="repo in repoStore.repos" :key="repo.id" :repo="repo" @open="openRepo" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.rs-home {
  display: grid;
  gap: var(--rs-space-6);
}

.rs-recent {
  padding: var(--rs-space-6);
}

.rs-recent__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--rs-space-3);
  margin-bottom: var(--rs-space-5);
}

.rs-recent__count {
  font-size: var(--rs-text-xs);
}

.rs-recent__list {
  display: grid;
  gap: var(--rs-space-3);
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}

.rs-recent__loading {
  padding: var(--rs-space-2) 0;
}

@media (max-width: 720px) {
  .rs-recent {
    padding: var(--rs-space-5) var(--rs-space-4);
  }

  .rs-recent__list {
    grid-template-columns: 1fr;
  }
}
</style>
