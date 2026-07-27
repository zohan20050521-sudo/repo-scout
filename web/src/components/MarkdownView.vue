<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown, renderMarkdownWithAnchors } from '@/composables/useMarkdown'

const props = withDefaults(
  defineProps<{
    source: string
    /** 为标题生成本地锚点 id（报告目录用） */
    anchors?: boolean
  }>(),
  { anchors: false },
)

/**
 * 这里的 v-html 消费的是 renderMarkdown 的输出：
 * markdown-it 以 html:false 运行，源文本中的原始 HTML 已被转义，
 * 链接协议也已过滤，因此不存在把模型输出原样注入 DOM 的路径。
 */
const html = computed(() =>
  props.anchors ? renderMarkdownWithAnchors(props.source) : renderMarkdown(props.source),
)
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="rs-markdown" v-html="html" />
</template>
