import client from './client'

export interface Project {
  id: number
  projectKey: string
  name: string
  repoUrl?: string
  techStack?: string
  status: string
  lastEventAt?: string
}

export interface TimelineEvent {
  eventId: string
  eventType: string
  appKey?: string
  branch?: string
  commitSha?: string
  author?: string
  occurredAt: string
  summary?: string
  summaryStatus: 'PENDING' | 'DONE' | 'FAILED'
  iterationId?: number
  iterationTitle?: string
  files?: { path: string; kind: string; addLines: number; delLines: number }[]
}

export interface Release {
  version: string
  baseCommit?: string
  tag?: string
  env?: string
  releasedAt?: string
  releaseNote?: string
}

export interface CompareChange {
  type: string
  sha?: string
  author?: string
  occurredAt: string
  summary?: string
}

export interface CompareReport {
  fromVersion: string
  toVersion: string
  stats: { filesChanged: number; addLines: number; delLines: number; commits: number }
  changes: CompareChange[]
  apis: unknown[]
  dependencies: unknown[]
  configs: unknown[]
  schemas: unknown[]
}

export interface QaReference {
  id: string
  type: string
  sha?: string
  title: string
}

export interface QaAnswer {
  answer: string
  references: QaReference[]
  model?: string
}

export const authApi = {
  login: (username: string, password: string): Promise<{ token: string; displayName: string; role: string }> =>
    client.post('/auth/login', { username, password })
}

export const projectApi = {
  list: (): Promise<Project[]> => client.get('/projects'),
  active: (): Promise<Project[]> => client.get('/projects/active'),
  create: (data: { projectKey: string; name: string; repoUrl?: string }): Promise<{ apiKey: string; apiSecret: string }> =>
    client.post('/projects', data),
  setStatus: (projectKey: string, status: string): Promise<void> =>
    client.put(`/projects/${projectKey}/status`, { status })
}

export const timelineApi = {
  query: (projectKey: string, params: Record<string, string>): Promise<TimelineEvent[]> =>
    client.get(`/projects/${projectKey}/timeline`, { params })
}

export const releaseApi = {
  list: (projectKey: string): Promise<Release[]> => client.get(`/projects/${projectKey}/releases`),
  compare: (projectKey: string, from: string, to: string): Promise<CompareReport> =>
    client.get(`/projects/${projectKey}/compare`, { params: { from, to } }),
  generateReleaseNotes: (projectKey: string, fromVersion: string, toVersion: string): Promise<{ content: string; model: string }> =>
    client.post(`/projects/${projectKey}/releases/release-notes`, { fromVersion, toVersion })
}

export const qaApi = {
  ask: (projectKey: string, question: string, modelId?: number): Promise<QaAnswer> =>
    client.post(`/projects/${projectKey}/qa`, { question, modelId })
}

export interface DashboardStats {
  projectCount: number
  appCount: number
  todayChanges: number
  releaseCount: number
}

export interface RecentRelease {
  version: string
  project: string
  releasedAt: string
  summary?: string
}

export interface TrendDay {
  day: string
  changes: number
  releases: number
}

export interface Credential {
  id: number
  apiKey: string
  scope: string
  status: string
  expiresAt?: string
  createdAt: string
}

export interface AppInfo {
  id: number
  projectId: number
  appKey: string
  name: string
  techStack?: string
  owner?: string
}

export const dashboardApi = {
  stats: (): Promise<DashboardStats> => client.get('/dashboard/stats'),
  recentReleases: (): Promise<RecentRelease[]> => client.get('/dashboard/recent-releases'),
  trend: (): Promise<TrendDay[]> => client.get('/dashboard/trend')
}

export const credentialApi = {
  list: (projectKey: string): Promise<Credential[]> => client.get(`/projects/${projectKey}/credentials`),
  rotate: (projectKey: string): Promise<{ apiKey: string; apiSecret: string }> => client.post(`/projects/${projectKey}/credentials/rotate`),
  revoke: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/credentials/${id}`)
}

export const applicationApi = {
  list: (projectKey: string): Promise<AppInfo[]> => client.get(`/projects/${projectKey}/applications`),
  create: (projectKey: string, data: { appKey: string; name: string; techStack?: string; owner?: string }): Promise<AppInfo> =>
    client.post(`/projects/${projectKey}/applications`, data),
  update: (projectKey: string, appKey: string, data: { name?: string; techStack?: string; owner?: string }): Promise<AppInfo> =>
    client.put(`/projects/${projectKey}/applications/${appKey}`, data)
}

export interface FileHistoryEntry {
  eventId: string
  eventType?: string
  commitSha?: string
  author?: string
  branch?: string
  commitMessage?: string
  occurredAt: string
  filePath?: string
  changeKind?: string
  addLines?: number
  delLines?: number
  diffBlobRef?: string
  summary?: string
  diff?: string | null
  hasDiff?: boolean
}

export const fileApi = {
  history: (path: string, projectKey: string): Promise<FileHistoryEntry[]> =>
    client.get('/files/history', { params: { path, projectKey } })
}

// ========== Analysis APIs ==========

export interface BreakingChangeAlert {
  id: number
  changeType: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  detail: { identityKey?: string; message?: string; table?: string; column?: string }
  acknowledged: boolean
  createdAt: string
}

export interface ImpactResult {
  affectedNodeCount: number
  affectedServices: string[]
  directCallers: string[]
  suggestedRegression: string[]
}

export interface RiskScore {
  totalScore: number
  riskLevel: string
  subScores: Record<string, number>
  explanation: string
}

export interface HotspotFile {
  filePath: string
  changeCount: number
  authorCount?: number
  lastChanged?: string
}

export interface Hotspots {
  topChangedFiles: HotspotFile[]
  bugProneFiles: HotspotFile[]
  coChangedFiles: { file1: string; file2: string; coCount: number }[]
  moduleHotspots: { module: string; changes: number; authors: number; avgDiffSize: number }[]
}

export const analysisApi = {
  breakingChanges: (projectKey: string): Promise<BreakingChangeAlert[]> =>
    client.get(`/projects/${projectKey}/analysis/breaking-changes`),
  acknowledge: (projectKey: string, alertId: number): Promise<void> =>
    client.post(`/projects/${projectKey}/analysis/breaking-changes/${alertId}/acknowledge`),
  impact: (projectKey: string, fromVersion: string, toVersion: string): Promise<ImpactResult> =>
    client.get(`/projects/${projectKey}/analysis/impact`, { params: { fromVersion, toVersion } }),
  riskScore: (projectKey: string, fromVersion: string, toVersion: string): Promise<RiskScore> =>
    client.get(`/projects/${projectKey}/analysis/risk-score`, { params: { fromVersion, toVersion } }),
  riskScoreHistory: (projectKey: string): Promise<{ version: string; totalScore: number; explanation: string; createdAt: string }[]> =>
    client.get(`/projects/${projectKey}/analysis/risk-score/history`),
  hotspots: (projectKey: string, days?: number): Promise<Hotspots> =>
    client.get(`/projects/${projectKey}/analysis/hotspots`, { params: { days: days ?? 30 } }),
  topImpactEndpoints: (projectKey: string): Promise<{ endpoint: string; callerCount: number }[]> =>
    client.get(`/projects/${projectKey}/analysis/top-impact-endpoints`)
}

// ========== Subscription APIs ==========

export interface SubscriptionRule {
  id: number
  name: string
  channel: string
  enabled: boolean
  createdAt: string
  filter?: { projectKey?: string; eventTypes?: string[]; filePattern?: string }
}

// ========== Test Management APIs (MeterSphere-inspired) ==========

export interface TestCase {
  id: number
  title: string
  description?: string
  steps?: string
  testType: string
  priority: string
  relatedFiles?: string
  relatedApis?: string
  tags?: string
  requirementId?: number
  parentId?: number
  nodeType?: string
  updatedAt?: string
  execCount?: number
  lastStatus?: string
  runnable?: boolean
  bugs?: { id: number; title: string; severity: string; status: string; externalKey?: string }[]
}

export interface TestPlan {
  id: number
  name: string
  targetVersion?: string
  fromVersion?: string
  status: string
  total?: number
  passed?: number
  failed?: number
  passRate?: number
  progress?: number
  createdAt?: string
  items?: TestPlanItem[]
}

export interface TestPlanItem {
  id: number
  testCaseId: number
  sortOrder: number
  status: string
  executor?: string
  resultDetail?: string
  executedAt?: string
  title: string
  priority: string
  testType: string
  steps?: string
}

export const testPlanApi = {
  // 用例
  listCases: (projectKey: string, params: Record<string, unknown>): Promise<{ total: number; list: TestCase[] }> =>
    client.get(`/projects/${projectKey}/testplan/cases`, { params }),
  getCaseTree: (projectKey: string): Promise<TestCase[]> => client.get(`/projects/${projectKey}/testplan/cases/tree`),
  getCase: (projectKey: string, id: number): Promise<TestCase> => client.get(`/projects/${projectKey}/testplan/cases/${id}`),
  createCase: (projectKey: string, data: Record<string, unknown>): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/testplan/cases`, data),
  updateCase: (projectKey: string, id: number, data: Record<string, unknown>): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/cases/${id}`, data),
  deleteCase: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/testplan/cases/${id}`),
  linkCaseBug: (projectKey: string, caseId: number, bugId: number): Promise<void> =>
    client.post(`/projects/${projectKey}/testplan/cases/${caseId}/bugs`, { bugId }),
  unlinkCaseBug: (projectKey: string, caseId: number, bugId: number): Promise<void> =>
    client.delete(`/projects/${projectKey}/testplan/cases/${caseId}/bugs/${bugId}`),
  // AI 用例生成 + 需求追溯矩阵（对标 MeterSphere）
  aiGenerateCase: (projectKey: string, data: { eventId: string; requirementId?: number }): Promise<Record<string, any>> =>
    client.post(`/projects/${projectKey}/testplan/cases/ai-generate`, data, { timeout: 60000 }),
  traceMatrix: (projectKey: string, requirementId: number): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/testplan/traceability/requirements/${requirementId}`),

  // 计划
  listPlans: (projectKey: string, params?: { status?: string }): Promise<TestPlan[]> =>
    client.get(`/projects/${projectKey}/testplan/plans`, { params }),
  createPlan: (projectKey: string, data: Record<string, unknown>): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/testplan/plans`, data),
  deletePlan: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/testplan/plans/${id}`),
  updatePlanStatus: (projectKey: string, id: number, status: string): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/plans/${id}/status`, { status }),
  updatePlan: (projectKey: string, id: number, data: Record<string, unknown>): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/plans/${id}`, data),
  getPlan: (projectKey: string, id: number): Promise<TestPlan> => client.get(`/projects/${projectKey}/testplan/plans/${id}`),
  addPlanItems: (projectKey: string, id: number, testCaseIds: number[]): Promise<{ added: number }> =>
    client.post(`/projects/${projectKey}/testplan/plans/${id}/items`, { testCaseIds }),
  removePlanItem: (projectKey: string, planId: number, itemId: number): Promise<void> =>
    client.delete(`/projects/${projectKey}/testplan/plans/${planId}/items/${itemId}`),
  reorderPlanItem: (projectKey: string, planId: number, itemId: number, direction: 'UP' | 'DOWN'): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/plans/${planId}/items/${itemId}/reorder`, { direction }),
  executePlanItem: (projectKey: string, planId: number, itemId: number, data: Record<string, unknown>): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/plans/${planId}/items/${itemId}/execute`, data),
  getPlanReport: (projectKey: string, id: number): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/testplan/plans/${id}/report`),
  createPlanFromRecommendation: (projectKey: string, data: { fromVersion: string; toVersion: string; planName?: string }): Promise<Record<string, any>> =>
    client.post(`/projects/${projectKey}/testplan/plans/from-recommendation`, data),

  // 执行与趋势
  listExecutions: (projectKey: string, params: Record<string, unknown>): Promise<{ total: number; list: any[] }> =>
    client.get(`/projects/${projectKey}/testplan/executions`, { params }),
  recordExecution: (projectKey: string, data: Record<string, unknown>): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/testplan/executions`, data),
  executionTrend: (projectKey: string, days = 30): Promise<{ day: string; total: number; passed: number; failed: number }[]> =>
    client.get(`/projects/${projectKey}/testplan/trends/executions`, { params: { days } }),
  bugTrend: (projectKey: string, days = 30): Promise<{ day: string; p0: number; p1: number; p2: number; p3: number }[]> =>
    client.get(`/projects/${projectKey}/testplan/trends/bugs`, { params: { days } }),

  // 服务端执行器（http 步骤真实执行）
  runCase: (projectKey: string, caseId: number, data?: Record<string, unknown>): Promise<RunCaseResult> =>
    client.post(`/projects/${projectKey}/testplan/cases/${caseId}/run`, data ?? {}, { timeout: 60000 }),
  runPlan: (projectKey: string, planId: number): Promise<RunPlanResult> =>
    client.post(`/projects/${projectKey}/testplan/plans/${planId}/run`, {}, { timeout: 180000 }),

  // ===== 用例版本（对标 MeterSphere 用例历史） =====
  caseVersions: (projectKey: string, caseId: number): Promise<{ id: number; version: number; changedBy?: string; createdAt: string }[]> =>
    client.get(`/projects/${projectKey}/testplan/cases/${caseId}/versions`),
  caseVersionDetail: (projectKey: string, caseId: number, version: number): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/testplan/cases/${caseId}/versions/${version}`),
  restoreCaseVersion: (projectKey: string, caseId: number, version: number): Promise<void> =>
    client.post(`/projects/${projectKey}/testplan/cases/${caseId}/versions/${version}/restore`),

  // ===== 计划：环境绑定 & 追加场景项 =====
  addScenarioItems: (projectKey: string, planId: number, scenarioIds: number[]): Promise<{ added: number }> =>
    client.post(`/projects/${projectKey}/testplan/plans/${planId}/scenario-items`, { scenarioIds })
}

// ========== 用例 Excel 批量导入/导出 ==========
export const caseFileApi = {
  exportUrl: (projectKey: string): string => `/api/v1/projects/${projectKey}/testplan/cases/export`,
  import: (projectKey: string, file: File, onProgress?: (p: number) => void): Promise<{ imported: number; skipped: number }> => {
    const fd = new FormData()
    fd.append('file', file)
    return client.post(`/projects/${projectKey}/testplan/cases/import`, fd, {
      timeout: 120000,
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress ? (e: any) => onProgress(e.progress ?? 0) : undefined
    })
  }
}

// ========== 接口场景编排（对标 MeterSphere 场景自动化） ==========
export interface ScenarioStep {
  id?: number
  sortOrder?: number
  stepType: string      // HTTP / EXTRACT / ASSERT / IF
  name?: string
  config: Record<string, any>
}
export interface Scenario {
  id: number
  name: string
  description?: string
  enabled: boolean
  createdBy?: string
  updatedAt?: string
  steps?: ScenarioStep[]
}
export interface ScenarioRunResult {
  scenarioId: number
  scenarioName: string
  verdict: string
  durationMs: number
  variables: Record<string, any>
  steps: {
    index: number
    name?: string
    type: string
    status: string
    method?: string
    url?: string
    statusCode?: number
    durationMs?: number
    responseSnippet?: string
    error?: string
    assertions?: { type: string; expected: string; passed: boolean; message: string }[]
  }[]
}

export const scenarioApi = {
  list: (projectKey: string): Promise<Scenario[]> => client.get(`/projects/${projectKey}/scenarios`),
  detail: (projectKey: string, id: number): Promise<Scenario> => client.get(`/projects/${projectKey}/scenarios/${id}`),
  create: (projectKey: string, data: { name: string; description?: string; steps: ScenarioStep[] }): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/scenarios`, data),
  update: (projectKey: string, id: number, data: { name?: string; description?: string; enabled?: boolean; steps?: ScenarioStep[] }): Promise<void> =>
    client.put(`/projects/${projectKey}/scenarios/${id}`, data),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/scenarios/${id}`),
  run: (projectKey: string, id: number, data?: { environmentId?: number; overrides?: Record<string, any> }): Promise<ScenarioRunResult> =>
    client.post(`/projects/${projectKey}/scenarios/${id}/run`, data ?? {}, { timeout: 120000 })
}

// ========== 测试报告（可视化 + 分享） ==========
export interface TestReport {
  id: number
  planId?: number
  name: string
  status: string
  shareToken?: string
  createdAt: string
  summary?: Record<string, any>
}
export const reportApi = {
  list: (projectKey: string): Promise<TestReport[]> => client.get(`/projects/${projectKey}/testplan/reports`),
  detail: (projectKey: string, id: number): Promise<TestReport> => client.get(`/projects/${projectKey}/testplan/reports/${id}`),
  generate: (projectKey: string, planId: number): Promise<TestReport> =>
    client.post(`/projects/${projectKey}/testplan/reports/plans/${planId}/generate`),
  refreshShareToken: (projectKey: string, id: number): Promise<{ shareToken: string }> =>
    client.post(`/projects/${projectKey}/testplan/reports/${id}/refresh-share-token`),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/testplan/reports/${id}`),
  shareUrl: (token: string): string => `${window.location.origin}/open-api/v1/reports/share/${token}`
}

// ========== 性能测试（对标 MeterSphere 性能测试单机形态） ==========
export interface PerfTest {
  id: number
  name: string
  endpointId?: number
  method?: string
  path?: string
  concurrency: number
  durationSec: number
  status: string
  summary?: Record<string, any>
  createdAt: string
}
export const perfApi = {
  list: (projectKey: string): Promise<PerfTest[]> => client.get(`/projects/${projectKey}/perf`),
  create: (projectKey: string, data: { endpointId: number; name: string; concurrency?: number; durationSec?: number }): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/perf`, data),
  run: (projectKey: string, id: number, baseUrl?: string): Promise<Record<string, any>> =>
    client.post(`/projects/${projectKey}/perf/${id}/run`, { baseUrl }, { timeout: 300000 }),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/perf/${id}`)
}

// ========== 定时调度 ==========
export interface TestSchedule {
  id: number
  planId: number
  planName?: string
  name: string
  cron: string
  enabled: boolean
  lastRunAt?: string
}
export const scheduleApi = {
  list: (projectKey: string): Promise<TestSchedule[]> => client.get(`/projects/${projectKey}/testplan/schedules`),
  create: (projectKey: string, data: { planId: number; name: string; cron: string }): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/testplan/schedules`, data),
  update: (projectKey: string, id: number, data: { name?: string; cron?: string; enabled?: boolean }): Promise<void> =>
    client.put(`/projects/${projectKey}/testplan/schedules/${id}`, data),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/testplan/schedules/${id}`)
}

// ========== CI/CD 集成（Jenkins / GitHub Actions 触发） ==========
export interface CiTrigger {
  id: number
  planId: number
  planName?: string
  name: string
  triggerType: string
  enabled: boolean
}
export interface CiRunResult {
  planId: number
  planName: string
  total: number
  runnable: number
  skipped: number
  passed: number
  failed: number
  durationMs: number
  report?: TestReport
}
export const ciApi = {
  listTriggers: (projectKey: string): Promise<CiTrigger[]> => client.get(`/projects/${projectKey}/ci/triggers`),
  createTrigger: (projectKey: string, data: { planId: number; name: string; triggerType?: string; enabled?: boolean }): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/ci/triggers`, data),
  deleteTrigger: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/ci/triggers/${id}`),
  runByToken: (token: string, data: { projectKey: string; planId: number; generateReport?: boolean }): Promise<CiRunResult> =>
    client.post('/ci/run', data, { headers: { 'X-CI-Token': token }, timeout: 180000 })
}

// ========== UI 测试（Selenium 低代码浏览器自动化） ==========

export interface UiStep {
  type: string        // OPEN / CLICK / INPUT / ASSERT_TEXT / WAIT / ASSERT_URL
  selector?: string
  url?: string
  value?: string | number
}
export interface UiTest {
  id: number
  name: string
  description?: string
  baseUrl?: string
  steps: UiStep[]
  script?: string
  enabled: boolean
  status: string
  lastResult?: Record<string, any>
  createdAt?: string
  updatedAt?: string
}
export interface UiRunResult {
  testId: number
  testName: string
  verdict: string
  durationMs: number
  steps: {
    index: number
    type: string
    status: string
    selector?: string
    url?: string
    value?: string | number
    actual?: string
    expected?: string
    error?: string
  }[]
  error?: string
}

export const uiTestApi = {
  list: (projectKey: string): Promise<UiTest[]> => client.get(`/projects/${projectKey}/ui-tests`),
  detail: (projectKey: string, id: number): Promise<UiTest> => client.get(`/projects/${projectKey}/ui-tests/${id}`),
  create: (projectKey: string, data: Partial<UiTest>): Promise<{ id: number }> =>
    client.post(`/projects/${projectKey}/ui-tests`, data),
  update: (projectKey: string, id: number, data: Partial<UiTest>): Promise<void> =>
    client.put(`/projects/${projectKey}/ui-tests/${id}`, data),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/ui-tests/${id}`),
  run: (projectKey: string, id: number): Promise<UiRunResult> =>
    client.post(`/projects/${projectKey}/ui-tests/${id}/run`, {}, { timeout: 180000 })
}

// ========== 用例/计划执行结果（服务端执行器） ==========

export interface RunStepResult {
  index: number
  name?: string
  type: string
  method?: string
  url?: string
  status: string
  durationMs: number
  statusCode?: number
  responseSnippet?: string
  error?: string
  assertions?: { type: string; expected: string; passed: boolean; message: string }[]
}

export interface RunCaseResult {
  executionId: number
  verdict: string
  durationMs: number
  summary: string
  runnable: boolean
  steps: RunStepResult[]
}

export interface RunPlanResult {
  planId: number
  planName: string
  total: number
  runnable: number
  skipped: number
  passed: number
  failed: number
  durationMs: number
  results: {
    itemId: number
    testCaseId: number
    title: string
    verdict: string
    reason?: string
    durationMs: number
    steps: RunStepResult[]
  }[]
}

// ========== 环境自检（链路演练） ==========

export const diagnosticsApi = {
  serverCheck: (projectKey: string): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/diagnostics/server`),
  credentialCheck: (projectKey: string): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/diagnostics/credential`),
  sendSample: (projectKey: string): Promise<Record<string, any>> =>
    client.post(`/projects/${projectKey}/diagnostics/send-sample`, {}, { timeout: 30000 }),
  sampleStatus: (projectKey: string, eventId: string): Promise<Record<string, any>> =>
    client.get(`/projects/${projectKey}/diagnostics/sample`, { params: { eventId } })
}

// ========== Jira 同步 ==========

export interface JiraConfig {
  baseUrl?: string
  username?: string
  apiToken?: string
  jiraProjectKey?: string
  issueType?: string
  statusMap?: Record<string, string>
  enabled: boolean
  lastSyncAt?: string
}

export const jiraApi = {
  getConfig: (projectKey: string): Promise<JiraConfig> => client.get(`/projects/${projectKey}/jira/config`),
  saveConfig: (projectKey: string, data: JiraConfig): Promise<void> =>
    client.put(`/projects/${projectKey}/jira/config`, data),
  sync: (projectKey: string): Promise<{ imported: number }> => client.post(`/projects/${projectKey}/jira/sync`)
}

// ========== 飞书多维表格(Bitable) 同步 ==========

export interface FeishuConfig {
  appId?: string
  appToken?: string
  bugTableId?: string
  caseTableId?: string
  fieldMap?: Record<string, string>
  statusMap?: Record<string, string>
  enabled: boolean
  lastSyncAt?: string
}

export const feishuApi = {
  getConfig: (projectKey: string): Promise<FeishuConfig> => client.get(`/projects/${projectKey}/feishu/config`),
  saveConfig: (projectKey: string, data: FeishuConfig): Promise<void> =>
    client.put(`/projects/${projectKey}/feishu/config`, data),
  sync: (projectKey: string): Promise<{ bugs: number; cases: number }> =>
    client.post(`/projects/${projectKey}/feishu/sync`)
}

// ========== Bug 增强 ==========

export interface BugItem {
  id: number
  title: string
  severity: string
  status: string
  source?: string
  assignedTo?: string
  foundBy?: string
  foundVersion?: string
  fixedVersion?: string
  externalKey?: string
  createdAt?: string
  updatedAt?: string
  linkedCommits?: number
  linkedCases?: number
  requirementTitle?: string
  description?: string
  // 后端列表返回原始列（snake_case）
  assigned_to?: string
  found_by?: string
  found_version?: string
  fixed_version?: string
  changeCount?: number
  updated_at?: string
  created_at?: string
}
export const bugApi = {
  list: (projectKey: string, params?: { status?: string; severity?: string }): Promise<BugItem[]> =>
    client.get('/pm/bugs', { params: { projectKey, ...(params ?? {}) } }),
  create: (projectKey: string, data: Record<string, any>): Promise<Record<string, any>> =>
    client.post('/pm/bugs', data, { params: { projectKey } }),
  trace: (bugId: number): Promise<Record<string, any>> => client.get(`/pm/bugs/${bugId}/trace`),
  link: (bugId: number, changeEventId: string, linkType = 'FIX'): Promise<void> =>
    client.post(`/pm/bugs/${bugId}/link`, null, { params: { changeEventId, linkType } }),
  transition: (bugId: number, toStatus: string, fixedVersion?: string): Promise<void> =>
    client.put(`/pm/bugs/${bugId}/transition`, { toStatus, fixedVersion }),
  detail: (bugId: number): Promise<Record<string, any>> => client.get(`/pm/bugs/${bugId}/detail`),
  linkTestCase: (bugId: number, testCaseId: number): Promise<void> =>
    client.post(`/pm/bugs/${bugId}/test-cases`, { testCaseId }),
  unlinkTestCase: (bugId: number, testCaseId: number): Promise<void> =>
    client.delete(`/pm/bugs/${bugId}/test-cases/${testCaseId}`)
}

export const subscriptionApi = {
  list: (): Promise<SubscriptionRule[]> => client.get('/subscriptions'),
  create: (data: { name: string; workspaceId: number; userId: number; filter: Record<string, unknown>; channel?: string; webhookUrl?: string }): Promise<void> =>
    client.post('/subscriptions', data),
  toggle: (id: number, enabled: boolean): Promise<void> =>
    client.put(`/subscriptions/${id}`, null, { params: { enabled } }),
  delete: (id: number): Promise<void> => client.delete(`/subscriptions/${id}`),
  logs: (): Promise<{ id: number; channel: string; title: string; status: string; errorMsg?: string; createdAt: string }[]> =>
    client.get('/subscriptions/logs')
}

// ========== AI 模型配置 ==========

export interface ModelConfig {
  id: number
  name: string
  provider: string
  baseUrl: string
  apiKey?: string          // 列表返回脱敏值;编辑留空 = 保持原值
  modelName: string
  temperature?: number
  enabled: boolean
  default?: boolean
  updatedAt?: string
}

export interface ModelStatus {
  configured: boolean
  model: string
  provider?: string | null
  baseUrl?: string | null
  name?: string | null
  usable: boolean
}

// ========== PM 需求工作台 ==========

export * from './pm'

export const modelConfigApi = {
  list: (): Promise<ModelConfig[]> => client.get('/ai/models'),
  status: (): Promise<ModelStatus> => client.get('/ai/models/status'),
  create: (data: Partial<ModelConfig>): Promise<ModelConfig> => client.post('/ai/models', data),
  update: (id: number, data: Partial<ModelConfig>): Promise<ModelConfig> => client.put(`/ai/models/${id}`, data),
  remove: (id: number): Promise<void> => client.delete(`/ai/models/${id}`),
  enable: (id: number): Promise<void> => client.post(`/ai/models/${id}/enable`),
  disable: (id: number): Promise<void> => client.post(`/ai/models/${id}/disable`),
  setDefault: (id: number): Promise<void> => client.post(`/ai/models/${id}/default`),
  test: (id: number): Promise<{ ok: boolean; message: string }> => client.post(`/ai/models/${id}/test`)
}

// ========== API 调试与文档（对标 Apifox / Postman） ==========

export interface ApiParam { name: string; in: string; required: boolean; type: string; desc?: string }
export interface ApiEndpoint {
  id: number
  projectId: number
  appId?: number | null
  appKey?: string | null
  method: string
  path: string
  name?: string
  summary?: string
  tags: string[]
  params: ApiParam[]
  requestBody?: Record<string, unknown> | null
  responseSchema?: Record<string, unknown> | null
  mockResponse?: Record<string, unknown> | null
  source: string
}
export interface ApiDebugResult {
  status: number
  responseHeaders: Record<string, string>
  body: unknown
  durationMs: number
  error?: string | null
}
export interface ApiEnvironment { id: number; projectId: number; name: string; baseUrl?: string; headers: Record<string, string> }
export interface ApiTestCase {
  id: number
  projectId: number
  endpointId?: number | null
  name: string
  request?: Record<string, unknown> | null
  response?: Record<string, unknown> | null
  expectedStatus?: number | null
  lastStatus?: number | null
  lastDurationMs?: number | null
}

export const apiDebugApi = {
  list: (projectKey: string): Promise<ApiEndpoint[]> => client.get(`/projects/${projectKey}/apis`),
  sync: (projectKey: string): Promise<{ synced: number }> => client.post(`/projects/${projectKey}/apis/sync`),
  import: (projectKey: string, format: string, appId: number | null, content: string): Promise<{ imported: number }> =>
    client.post(`/projects/${projectKey}/apis/import`, { format, appId, content }),
  get: (projectKey: string, id: number): Promise<ApiEndpoint> => client.get(`/projects/${projectKey}/apis/${id}`),
  update: (projectKey: string, id: number, data: Partial<Omit<ApiEndpoint, 'id'>>): Promise<void> =>
    client.put(`/projects/${projectKey}/apis/${id}`, data),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/apis/${id}`),
  debug: (projectKey: string, id: number, request: Record<string, unknown>, baseUrl?: string): Promise<ApiDebugResult> =>
    client.post(`/projects/${projectKey}/apis/${id}/debug`, { request, baseUrl }),
  mock: (projectKey: string, id: number): Promise<Record<string, unknown>> => client.get(`/projects/${projectKey}/apis/${id}/mock`),
  environments: (projectKey: string): Promise<ApiEnvironment[]> => client.get(`/projects/${projectKey}/apis/environments`),
  saveEnvironment: (projectKey: string, data: Partial<ApiEnvironment>): Promise<void> =>
    client.post(`/projects/${projectKey}/apis/environments`, data),
  deleteEnvironment: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/apis/environments/${id}`),
  testCases: (projectKey: string): Promise<ApiTestCase[]> => client.get(`/projects/${projectKey}/apis/test-cases`),
  saveTestCase: (projectKey: string, data: Partial<ApiTestCase>): Promise<void> =>
    client.post(`/projects/${projectKey}/apis/test-cases`, data),
  deleteTestCase: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/apis/test-cases/${id}`)
}

// ========== 研发效能度量（P0-1，对标 TAPD 研效仪表盘） ==========

export interface DevMetrics {
  requirementDeliveryRate: number
  requirementDone: number
  requirementTotal: number
  changeThroughput: number
  avgDailyChanges: number
  bugEscapeRate: number
  bugTotal: number
  bugOpen: number
  releaseCount: number
  avgReleaseCycleDays?: number | null
  avgRequirementCycleDays?: number | null
  testPassRate: number
  testExecutions: number
}
export interface MetricTrendPoint {
  day: string
  changes: number
  requirements: number
  bugs: number
  executions: number
}
export const devMetricsApi = {
  overview: (projectKey: string): Promise<DevMetrics> => client.get('/metrics/overview', { params: { projectKey } }),
  trend: (projectKey: string, days = 30): Promise<MetricTrendPoint[]> =>
    client.get('/metrics/trend', { params: { projectKey, days } }),
  bugDistribution: (projectKey: string): Promise<{ severity: string; status: string; count: number }[]> =>
    client.get('/metrics/bugs/distribution', { params: { projectKey } }),
  requirementFlow: (projectKey: string): Promise<{ status: string; entries: number; avgDays: number }[]> =>
    client.get('/metrics/requirements/flow', { params: { projectKey } }),
  snapshot: (projectKey: string): Promise<{ success: boolean; period: string; metrics: DevMetrics }> =>
    client.post('/metrics/snapshot', null, { params: { projectKey } }),
  history: (projectKey: string): Promise<{ period: string; payload: DevMetrics; createdAt: string }[]> =>
    client.get('/metrics/history', { params: { projectKey } })
}

// ========== 自动化规则引擎（P0-3） ==========

export interface AutomationRule {
  id: number
  name: string
  triggerEvent: string
  action: string
  condition: Record<string, any>
  config: Record<string, any>
  enabled: boolean
  runCount?: number
  lastRunAt?: string
  createdAt?: string
}
export const automationRuleApi = {
  list: (projectKey: string): Promise<AutomationRule[]> => client.get('/automation-rules', { params: { projectKey } }),
  upsert: (projectKey: string, data: Partial<AutomationRule>): Promise<{ success: boolean; id: number }> =>
    client.post('/automation-rules', data, { params: { projectKey } }),
  delete: (projectKey: string, id: number): Promise<void> => client.delete(`/automation-rules/${id}`, { params: { projectKey } }),
  trigger: (projectKey: string, triggerEvent: string, payload: Record<string, any>): Promise<{ matched: number; executed: number; totalRules: number }> =>
    client.post('/automation-rules/trigger', { triggerEvent, payload }, { params: { projectKey } })
}

// ========== 反馈 → 需求/缺陷（P2） ==========

export interface Feedback {
  id: number
  source: string
  content: string
  status: string
  aiAnalysis?: string
  aiModel?: string
  convertedRequirementId?: number
  convertedBugId?: number
  createdAt: string
  requirementTitle?: string
  bugTitle?: string
}
export const feedbackApi = {
  list: (projectKey: string, status?: string): Promise<Feedback[]> =>
    client.get('/feedback', { params: { projectKey, status } }),
  create: (projectKey: string, data: { content: string; source?: string; createdBy?: string }): Promise<{ success: boolean; id: number }> =>
    client.post('/feedback', data, { params: { projectKey } }),
  analyze: (projectKey: string, id: number): Promise<{ feedbackId: number; type: string; title: string; priority: string; summary: string; aiGenerated: boolean; model?: string }> =>
    client.post(`/feedback/${id}/analyze`, null, { params: { projectKey }, timeout: 60000 }),
  convert: (projectKey: string, id: number, data: { type: string; title?: string; priority?: string; summary?: string }): Promise<{ success: boolean; type: string; targetId: number }> =>
    client.post(`/feedback/${id}/convert`, data, { params: { projectKey } }),
  ignore: (projectKey: string, id: number): Promise<void> => client.post(`/feedback/${id}/ignore`, null, { params: { projectKey } })
}

// ========== 项目成员与角色权限（P1） ==========

export interface ProjectMember {
  id: number
  userId: number
  role: string
  username: string
  displayName?: string
  createdAt: string
}
export const memberApi = {
  list: (projectKey: string): Promise<ProjectMember[]> => client.get(`/projects/${projectKey}/members`),
  add: (projectKey: string, data: { username: string; role: string }): Promise<{ success: boolean; userId: number; role: string }> =>
    client.post(`/projects/${projectKey}/members`, data),
  remove: (projectKey: string, id: number): Promise<void> => client.delete(`/projects/${projectKey}/members/${id}`),
  me: (projectKey: string, username = 'admin'): Promise<{ role: string; roles: string[] }> =>
    client.get(`/projects/${projectKey}/members/me`, { params: { username } })
}
