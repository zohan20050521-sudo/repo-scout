<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="rs-empty">
    <div class="rs-empty__mark" aria-hidden="true">
      <slot name="icon">·</slot>
    </div>
    <p class="rs-empty__title">{{ title }}</p>
    <p v-if="description" class="rs-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="rs-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.rs-empty {
  padding: var(--rs-space-8) var(--rs-space-5);
  text-align: center;
}

.rs-empty__mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin: 0 auto var(--rs-space-4);
  border: 1px dashed var(--rs-line-strong);
  border-radius: var(--rs-radius-md);
  background: var(--rs-surface-muted);
  color: var(--rs-ink-300);
  font-size: 20px;
}

.rs-empty__title {
  color: var(--rs-ink-800);
  font-weight: 600;
}

.rs-empty__desc {
  max-width: 420px;
  margin: var(--rs-space-2) auto 0;
  color: var(--rs-ink-500);
  font-size: var(--rs-text-sm);
}

.rs-empty__action {
  margin-top: var(--rs-space-5);
}
</style>
