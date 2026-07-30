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
  iterationTitle?: string
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
    client.get(`/projects/${projectKey}/compare`, { params: { from, to } })
}

export const qaApi = {
  ask: (projectKey: string, question: string): Promise<QaAnswer> =>
    client.post(`/projects/${projectKey}/qa`, { question })
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

export const fileApi = {
  history: (path: string, projectKey: string): Promise<unknown[]> =>
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
