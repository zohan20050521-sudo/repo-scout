import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/element-overrides.css'
import './styles/markdown.css'
import App from './App.vue'
import { router } from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
