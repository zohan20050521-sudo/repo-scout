<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowDown, ArrowUp, TopRight } from '@element-plus/icons-vue'
import { formatScore, scoreLevel, scoreLevelLabel } from '@/composables/useFormat'
import type { Citation } from '@/types/api'

const props = defineProps<{ citation: Citation }>()

const expanded = ref(false)
const COLLAPSED_LENGTH = 160

const needsToggle = computed(() => props.citation.excerpt.length > COLLAPSED_LENGTH)
const visibleExcerpt = computed(() =>
  expanded.value || !needsToggle.value
    ? props.citation.excerpt
    : `${props.citation.excerpt.slice(0, COLLAPSED_LENGTH)}…`,
)
const level = computed(() => scoreLevel(props.citation.score))
</script>

<template>
  <article class="rs-citation">
    <header class="rs-citation__head">
      <span class="rs-citation__path rs-mono">{{ citation.filePath }}</span>
      <span class="rs-citation__badges">
        <span class="rs-citation__chunk rs-mono">块 #{{ citation.chunkIndex }}</span>
        <span class="rs-citation__score rs-mono" :class="`is-${level}`">
          {{ scoreLevelLabel(citation.score) }} {{ formatScore(citation.score) }}
        </span>
      </span>
    </header>

    <p class="rs-citation__excerpt">{{ visibleExcerpt }}</p>

    <footer class="rs-citation__foot">
      <button
        v-if="needsToggle"
        type="button"
        class="rs-citation__toggle"
        @click="expanded = !expanded"
      >
        <el-icon><component :is="expanded ? ArrowUp : ArrowDown" /></el-icon>
        {{ expanded ? '收起摘录' : '展开完整摘录' }}
      </button>
      <a
        class="rs-citation__link"
        :href="citation.url"
        target="_blank"
        rel="noopener noreferrer"
        :aria-label="`在 GitHub 打开 ${citation.filePath}`"
      >
        在 GitHub 打开
        <el-icon><TopRight /></el-icon>
      </a>
    </footer>
  </article>
</template>

<style scoped>
.rs-citation {
  padding: var(--rs-space-3) var(--rs-space-4);
  border: 1px solid var(--rs-line);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface);
}

.rs-citation__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--rs-space-2);
}

.rs-citation__path {
  color: var(--rs-ink-900);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.rs-citation__badges {
  display: flex;
  align-items: center;
  gap: var(--rs-space-2);
  font-size: var(--rs-text-xs);
}

.rs-citation__chunk {
  padding: 1px 6px;
  border-radius: var(--rs-radius-pill);
  background: var(--rs-surface-sunken);
  color: var(--rs-ink-500);
}

.rs-citation__score {
  padding: 1px 6px;
  border-radius: var(--rs-radius-pill);
}

.rs-citation__score.is-high {
  background: var(--rs-success-50);
  color: var(--rs-success-500);
}

.rs-citation__score.is-medium {
  background: var(--rs-brand-50);
  color: var(--rs-brand-600);
}

.rs-citation__score.is-low {
  background: var(--rs-surface-sunken);
  color: var(--rs-ink-500);
}

.rs-citation__excerpt {
  margin-top: var(--rs-space-2);
  color: var(--rs-ink-700);
  font-size: var(--rs-text-sm);
  line-height: var(--rs-leading-normal);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.rs-citation__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--rs-space-3);
  margin-top: var(--rs-space-2);
}

.rs-citation__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: none;
  color: var(--rs-brand-600);
  cursor: pointer;
  font: inherit;
  font-size: var(--rs-text-xs);
}

.rs-citation__toggle:hover {
  color: var(--rs-brand-700);
  text-decoration: underline;
}

.rs-citation__link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  font-size: var(--rs-text-xs);
}
</style>
