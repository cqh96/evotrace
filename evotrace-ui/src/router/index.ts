import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
    {
      path: '/',
      component: MainLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '项目总览' } },
        { path: 'timeline', name: 'timeline', component: () => import('../views/TimelineView.vue'), meta: { title: '演化时间线' } },
        { path: 'compare', name: 'compare', component: () => import('../views/CompareView.vue'), meta: { title: '版本对比' } },
        { path: 'qa', name: 'qa', component: () => import('../views/QaView.vue'), meta: { title: 'AI 演化问答' } },
        { path: 'integration', name: 'integration', component: () => import('../views/IntegrationView.vue'), meta: { title: '接入管理' } },
        { path: 'analysis', name: 'analysis', component: () => import('../views/AnalysisView.vue'), meta: { title: '智能分析' } },
        { path: 'subscriptions', name: 'subscriptions', component: () => import('../views/SubscriptionView.vue'), meta: { title: '变更订阅' } },
        { path: 'pm', name: 'pm', component: () => import('../views/PMDashboardView.vue'), meta: { title: 'PM 需求看板' } },
        { path: 'qa-dashboard', name: 'qa-dashboard', component: () => import('../views/QADashboardView.vue'), meta: { title: 'QA 测试面板' } },
        { path: 'code-review', name: 'code-review', component: () => import('../views/CodeReviewView.vue'), meta: { title: 'AI 代码审查' } },
        { path: 'model-config', name: 'model-config', component: () => import('../views/ModelConfigView.vue'), meta: { title: 'AI 模型配置' } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('evotrace_token')) {
    return '/login'
  }
})

export default router
