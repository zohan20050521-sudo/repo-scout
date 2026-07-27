<script setup lang="ts">
import type { MarkdownHeading } from '@/composables/useMarkdown'

defineProps<{ headings: MarkdownHeading[] }>()
const emit = defineEmits<{ scrollTo: [anchor: string] }>()

function handleClick(anchor: string): void {
  emit('scrollTo', anchor)
}
</script>

<template>
  <nav v-if="headings.length" class="rs-report__toc" aria-label="报告小节">
    <button
      v-for="heading in headings"
      :key="heading.anchor"
      type="button"
      class="rs-report__toc-item"
      @click="handleClick(heading.anchor)"
    >
      {{ heading.text }}
    </button>
  </nav>
</template>

<style scoped>
.rs-report__toc {
  display: flex;
  position: sticky;
  top: calc(var(--rs-header-height) + var(--rs-space-4));
  flex-direction: column;
  align-self: start;
  gap: 2px;
  padding-left: var(--rs-space-2);
  border-left: 2px solid var(--rs-line);
}

.rs-report__toc-item {
  padding: var(--rs-space-1) var(--rs-space-2);
  border: none;
  border-radius: var(--rs-radius-sm);
  background: none;
  color: var(--rs-ink-500);
  cursor: pointer;
  font: inherit;
  font-size: var(--rs-text-xs);
  text-align: left;
}

.rs-report__toc-item:hover {
  background: var(--rs-brand-50);
  color: var(--rs-brand-700);
}

@media (max-width: 900px) {
  .rs-report__toc {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
    padding-left: 0;
    border-left: none;
  }

  .rs-report__toc-item {
    border: 1px solid var(--rs-line);
  }
}
</style>
