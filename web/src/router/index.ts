import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: 'repo-scout · 接入仓库' },
  },
  {
    path: '/repos/:repoId(\\d+)',
    name: 'repo-workspace',
    component: () => import('@/views/RepoWorkspaceView.vue'),
    meta: { title: 'repo-scout · 仓库工作区' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: 'repo-scout · 页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = to.meta.title
  if (typeof title === 'string') document.title = title
})
