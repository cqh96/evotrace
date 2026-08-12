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
        { path: 'qa', name: 'qa', component: () => import('../views/QaView.vue'), meta: { title: 'AI 问答' } },
        { path: 'integration', name: 'integration', component: () => import('../views/IntegrationView.vue'), meta: { title: '接入管理' } },
        { path: 'analysis', name: 'analysis', component: () => import('../views/AnalysisView.vue'), meta: { title: '智能分析' } },
        { path: 'subscriptions', name: 'subscriptions', component: () => import('../views/SubscriptionView.vue'), meta: { title: '变更订阅' } },
        { path: 'pm', name: 'pm', component: () => import('../views/PMDashboardView.vue'), meta: { title: '需求看板' } },
        { path: 'qa-dashboard', name: 'qa-dashboard', component: () => import('../views/QADashboardView.vue'), meta: { title: '测试用例' } },
        { path: 'code-review', name: 'code-review', component: () => import('../views/CodeReviewView.vue'), meta: { title: '代码审查' } },
        { path: 'api-debug', name: 'api-debug', component: () => import('../views/ApiDebugView.vue'), meta: { title: 'API 调试' } },
        { path: 'scenario', name: 'scenario', component: () => import('../views/ScenarioView.vue'), meta: { title: '场景编排' } },
        { path: 'test-report', name: 'test-report', component: () => import('../views/TestReportView.vue'), meta: { title: '测试报告' } },
        { path: 'perf-test', name: 'perf-test', component: () => import('../views/PerformanceTestView.vue'), meta: { title: '性能测试' } },
        { path: 'ui-test', name: 'ui-test', component: () => import('../views/UiTestView.vue'), meta: { title: 'UI 测试' } },
        { path: 'ci-integration', name: 'ci-integration', component: () => import('../views/CiIntegrationView.vue'), meta: { title: 'CI 集成' } },
        { path: 'model-config', name: 'model-config', component: () => import('../views/ModelConfigView.vue'), meta: { title: 'AI 模型配置' } },
        { path: 'workbench', name: 'workbench', component: () => import('../views/WorkbenchView.vue'), meta: { title: '个人工作台' } },
        { path: 'metrics', name: 'metrics', component: () => import('../views/DevMetricsView.vue'), meta: { title: '研效度量' } },
        { path: 'bugs', name: 'bugs', component: () => import('../views/BugManagementView.vue'), meta: { title: '缺陷管理' } },
        { path: 'automation', name: 'automation', component: () => import('../views/AutomationRuleView.vue'), meta: { title: '自动化规则' } },
        { path: 'trace-governance', name: 'trace-governance', component: () => import('../views/TraceGovernanceView.vue'), meta: { title: '链路治理中心' } },
        { path: 'release-cockpit', name: 'release-cockpit', component: () => import('../views/ReleaseCockpitView.vue'), meta: { title: '版本全景' } },
        { path: 'feedback', name: 'feedback', component: () => import('../views/FeedbackView.vue'), meta: { title: '反馈管理' } },
        { path: 'members', name: 'members', component: () => import('../views/MemberView.vue'), meta: { title: '项目成员' } },
        { path: 'plugin-market', name: 'plugin-market', component: () => import('../views/PluginMarketView.vue'), meta: { title: '解析器插件市场' } },
        { path: 'gitlab-repos', name: 'gitlab-repos', component: () => import('../views/GitlabReposView.vue'), meta: { title: 'GitLab 仓库' } }
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
