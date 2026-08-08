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
  create: (data: { projectKey: string; name: string; repoUrl?: string }): Promise<{ apiKey: string; apiSecret: string }> =>
    client.post('/projects', data)
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
    client.post(`/projects/${projectKey}/testplan/plans/${planId}/run`, {}, { timeout: 180000 })
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

// ========== Bug 增强 ==========

export const bugApi = {
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
