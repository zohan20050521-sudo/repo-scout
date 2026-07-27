<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRepoStore } from '@/stores/repo'
import { useChatStore } from '@/stores/chat'
import { useReportStore } from '@/stores/report'
import RepoOverview from '@/components/RepoOverview.vue'
import IndexStatusCard from '@/components/IndexStatusCard.vue'
import ChatPanel from '@/components/ChatPanel.vue'
import ReportPanel from '@/components/ReportPanel.vue'
import ErrorPanel from '@/components/ErrorPanel.vue'

const route = useRoute()
const router = useRouter()
const repoStore = useRepoStore()
const chatStore = useChatStore()
const reportStore = useReportStore()

const repoId = computed(() => Number(route.params.repoId))
const repo = computed(() => repoStore.currentRepo)
const indexed = computed(() => repoStore.indexStatus?.indexed === true)

/** 刷新后按路由 repoId 重新拉取，不依赖内存里的导航对象 */
async function load(id: number): Promise<void> {
  repoStore.resetIndexState()
  chatStore.bindRepo(id)
  reportStore.resetForRepo(id)
  await repoStore.fetchRepo(id)
  if (repoStore.currentRepo) await repoStore.fetchIndexStatus(id)
}

watch(
  repoId,
  (id) => {
    if (Number.isFinite(id)) void load(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="rs-workspace">
    <el-skeleton v-if="repoStore.repoLoading && !repo" class="rs-workspace__skeleton" :rows="4" animated />

    <ErrorPanel
      v-else-if="repoStore.repoError"
      title="仓库信息没能加载"
      :error="repoStore.repoError"
      retryable
      @retry="load(repoId)"
    />

    <template v-if="repo">
      <RepoOverview :repo="repo" />

      <div class="rs-workspace__grid">
        <ChatPanel class="rs-workspace__chat" :repo-id="repo.id" :indexed="indexed" />
        <IndexStatusCard class="rs-workspace__index" :repo-id="repo.id" />
      </div>

      <ReportPanel :repo="repo" :indexed="indexed" />
    </template>

    <div v-if="repoStore.repoError" class="rs-workspace__back">
      <el-button text @click="router.push('/')">返回接入页选择其他仓库</el-button>
    </div>
  </div>
</template>

<style scoped>
.rs-workspace {
  display: grid;
  gap: var(--rs-space-6);
}

.rs-workspace__skeleton {
  padding: var(--rs-space-6);
  border-radius: var(--rs-radius-lg);
  background: var(--rs-surface);
  border: 1px solid var(--rs-line);
}

.rs-workspace__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(300px, 1fr);
  gap: var(--rs-space-6);
  align-items: start;
}

.rs-workspace__back {
  text-align: center;
}

@media (max-width: 1020px) {
  .rs-workspace__grid {
    grid-template-columns: 1fr;
  }

  .rs-workspace__index {
    order: -1;
  }
}
</style>
