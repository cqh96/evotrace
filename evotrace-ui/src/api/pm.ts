import client from './client'

// ========== PM 需求工作台 ==========

export type RequirementStatus = 'DRAFT' | 'REVIEW' | 'DEVELOPING' | 'TESTING' | 'DONE'
export type TaskStatus = 'TODO' | 'DOING' | 'DONE'

export interface Requirement {
  id: number
  reqKey?: string
  title: string
  priority?: string
  status: RequirementStatus
  pm?: string
  assignee?: string
  targetVersion?: string
  prototypeUrl?: string
  businessValue?: string
  userStory?: string
  acceptanceCriteria?: string
  estimateDays?: number
  techLead?: string
  linkedCommits?: number
  testCases?: number
  openBugs?: number
  totalBugs?: number
  docVersion?: number
  taskCount?: number
  taskDone?: number
  createdAt?: string
}

export interface RequirementDetail extends Requirement {
  description?: string
  source?: string
  designUrl?: string
  iterationId?: number
  iterationTitle?: string
  updatedAt?: string
  taskTotal?: number
  prototypeUpdatedAt?: string
}

export interface RequirementInput {
  title: string
  description?: string
  priority?: string
  status?: RequirementStatus
  targetVersion?: string
  assignedTo?: string
  productManager?: string
  prototypeUrl?: string
  designUrl?: string
  businessValue?: string
  userStory?: string
  acceptanceCriteria?: string
  estimateDays?: number
  techLead?: string
}

export interface AiExpandResult {
  generated: boolean
  message?: string
  model?: string
  businessValue?: string
  userStory?: string
  acceptanceCriteria?: string
  estimateDays?: string
  techLead?: string
}

export interface TraceEntry {
  changes: unknown[]
  testCases: unknown[]
  bugs: unknown[]
  releases: unknown[]
}

export interface RequirementDocument {
  requirementId?: number
  version: number
  title?: string
  content?: string
  createdBy?: string
  createdAt?: string
}

export interface DocumentSaveResult {
  version: number
  title: string
  createdAt?: string
}

export interface AiDraftResult {
  content: string
  model?: string
  generated: boolean
  message?: string
}

export interface PrototypeElementProps {
  text?: string
  placeholder?: string
  options?: string
  brand?: string
  columns?: number
  rows?: number
  src?: string
}

export interface PrototypeElement {
  id: string
  type: string
  x: number
  y: number
  w: number
  h: number
  props: PrototypeElementProps
  linkTo?: string
}

export interface PrototypePage {
  id: string
  name: string
  width: number
  height: number
  elements: PrototypeElement[]
}

export interface PrototypeData {
  pages: PrototypePage[]
  updatedBy?: string
  updatedAt?: string
}

export interface PrototypeAiResult {
  pages: PrototypePage[]
  model?: string
  generated: boolean
  message?: string
}

export interface RequirementTask {
  id: number
  title: string
  assignee?: string
  status: TaskStatus
  estimateHours?: number
  priority?: string
  sortOrder?: number
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface RoadmapVersion {
  version: string
  type: 'RELEASED' | 'TARGET'
  releasedAt?: string
  total: number
  done: number
  doneRate: number
}

export interface StatusFlow {
  byStatus: { status: string; entries: number; openCount: number; avgDays: number; maxDays: number }[]
  transitions: { from: string; to: string; count: number }[]
  trend: { day: string; count: number }[]
  avgCycleDays: number | null
}

export interface StatusHistoryEntry {
  status: string
  fromStatus?: string
  actor?: string
  enteredAt?: string
  leftAt?: string | null
  durationDays?: number | null
}

// ========== 需求文档智能解析 ==========

export interface ParsedCase {
  title: string
  testType?: string
  priority?: string
  steps?: string
  selected?: boolean
}

export interface ParsedRequirement {
  title: string
  userStory?: string
  acceptanceCriteria?: string
  priority?: string
  businessValue?: string
  suggestedCases?: ParsedCase[]
}

export interface ParsePreview {
  parsed: boolean
  parseId: number
  message?: string
  model?: string
  docTitle?: string
  requirements?: ParsedRequirement[]
}

export interface ImportConfirmResult {
  success: boolean
  requirementIds: number[]
  caseIds: number[]
}

export const pmApi = {
  // 看板
  list: (projectKey: string, status?: string): Promise<Requirement[]> =>
    client.get('/pm/requirements', { params: { projectKey, status: status ?? undefined } }),
  create: (projectKey: string, data: RequirementInput): Promise<Requirement> =>
    client.post('/pm/requirements', data, { params: { projectKey } }),
  updateStatus: (id: number, status: string): Promise<void> =>
    client.put(`/pm/requirements/${id}/status?status=${status}&actor=PM`),

  // 看板统计与通知（现有 PM 视图依赖）
  dashboard: (projectKey: string): Promise<Record<string, unknown>> =>
    client.get('/pm/dashboard', { params: { projectKey } }),
  notifications: (projectKey: string, role = 'PM'): Promise<Record<string, unknown>[]> =>
    client.get('/pm/notifications', { params: { projectKey, role } }),
  notificationRead: (id: number): Promise<void> =>
    client.put(`/pm/notifications/${id}/read`),
  qualityGateCheck: (projectKey: string, targetVersion: string): Promise<Record<string, unknown>> =>
    client.post('/pm/quality-gate/check', null, { params: { projectKey, targetVersion } }),
  qualityGateHistory: (projectKey: string): Promise<Record<string, unknown>[]> =>
    client.get('/pm/quality-gate/history', { params: { projectKey } }),
  testRecommendation: (projectKey: string): Promise<Record<string, unknown>> =>
    client.get('/pm/test-recommendation', { params: { projectKey } }),

  // 详情与溯源
  detail: (projectKey: string, id: number): Promise<RequirementDetail> =>
    client.get(`/pm/requirements/${id}/detail`, { params: { projectKey } }),
  trace: (projectKey: string, id: number): Promise<TraceEntry> =>
    client.get(`/pm/requirements/${id}/trace`, { params: { projectKey } }),
  statusHistory: (projectKey: string, id: number): Promise<StatusHistoryEntry[]> =>
    client.get(`/pm/requirements/${id}/status-history`, { params: { projectKey } }),

  // AI 扩写
  aiExpand: (projectKey: string, input: { title: string; description?: string; priority?: string }): Promise<AiExpandResult> =>
    client.post('/pm/requirements/ai-expand', input, { params: { projectKey }, timeout: 60000 }),

  // 文档
  document: (projectKey: string, id: number): Promise<RequirementDocument> =>
    client.get(`/pm/requirements/${id}/document`, { params: { projectKey } }),
  documentVersions: (projectKey: string, id: number): Promise<RequirementDocument[]> =>
    client.get(`/pm/requirements/${id}/document/versions`, { params: { projectKey } }),
  documentVersion: (projectKey: string, id: number, version: number): Promise<RequirementDocument> =>
    client.get(`/pm/requirements/${id}/document/versions/${version}`, { params: { projectKey } }),
  documentSave: (projectKey: string, id: number, data: { title?: string; content: string }): Promise<DocumentSaveResult> =>
    client.post(`/pm/requirements/${id}/document`, data, { params: { projectKey }, timeout: 60000 }),
  documentRollback: (projectKey: string, id: number, version: number): Promise<DocumentSaveResult> =>
    client.post(`/pm/requirements/${id}/document/rollback`, { version }, { params: { projectKey } }),
  documentAiDraft: (projectKey: string, id: number, prompt?: string): Promise<AiDraftResult> =>
    client.post(`/pm/requirements/${id}/document/ai-draft`, { prompt }, { params: { projectKey }, timeout: 60000 }),

  // 原型
  prototype: (projectKey: string, id: number): Promise<PrototypeData> =>
    client.get(`/pm/requirements/${id}/prototype`, { params: { projectKey } }),
  prototypeSave: (projectKey: string, id: number, pages: PrototypePage[]): Promise<{ success: boolean; pagesSaved: boolean }> =>
    client.put(`/pm/requirements/${id}/prototype`, { pages }, { params: { projectKey } }),
  prototypeAiGenerate: (projectKey: string, id: number, prompt?: string): Promise<PrototypeAiResult> =>
    client.post(`/pm/requirements/${id}/prototype/ai-generate`, { prompt }, { params: { projectKey }, timeout: 60000 }),

  // 任务
  tasks: (projectKey: string, id: number): Promise<RequirementTask[]> =>
    client.get(`/pm/requirements/${id}/tasks`, { params: { projectKey } }),
  taskCreate: (projectKey: string, id: number, data: Partial<RequirementTask>): Promise<RequirementTask> =>
    client.post(`/pm/requirements/${id}/tasks`, data, { params: { projectKey } }),
  taskUpdate: (projectKey: string, id: number, taskId: number, data: Partial<RequirementTask>): Promise<void> =>
    client.put(`/pm/requirements/${id}/tasks/${taskId}`, data, { params: { projectKey } }),
  taskStatus: (projectKey: string, id: number, taskId: number, status: TaskStatus): Promise<void> =>
    client.put(`/pm/requirements/${id}/tasks/${taskId}/status`, { status }, { params: { projectKey } }),
  taskReorder: (projectKey: string, id: number, order: { id: number; sortOrder: number }[]): Promise<void> =>
    client.put(`/pm/requirements/${id}/tasks/reorder`, { order }, { params: { projectKey } }),
  taskDelete: (projectKey: string, id: number, taskId: number): Promise<void> =>
    client.delete(`/pm/requirements/${id}/tasks/${taskId}`, { params: { projectKey } }),

  // 生命周期
  roadmap: (projectKey: string): Promise<RoadmapVersion[]> =>
    client.get('/pm/lifecycle/roadmap', { params: { projectKey } }),
  statusFlow: (projectKey: string): Promise<StatusFlow> =>
    client.get('/pm/lifecycle/status-flow', { params: { projectKey } }),

  // 需求文档智能解析
  parseLink: (projectKey: string, url: string, prdText?: string): Promise<ParsePreview> =>
    client.post('/pm/requirements/parse-link', { url, prdText }, { params: { projectKey }, timeout: 150000 }),
  parseDoc: (projectKey: string, file: File): Promise<ParsePreview> => {
    const form = new FormData()
    form.append('file', file)
    return client.post('/pm/requirements/parse-doc', form, { params: { projectKey }, timeout: 150000 })
  },
  importConfirm: (projectKey: string, parseId: number, requirements: ParsedRequirement[]): Promise<ImportConfirmResult> =>
    client.post('/pm/requirements/import-confirm', { parseId, requirements }, { params: { projectKey }, timeout: 60000 })
}
