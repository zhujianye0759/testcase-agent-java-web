export type FewShotPolicy = 'AUTO' | 'NONE'

export interface CreateTaskPayload {
  taskMode: 'FEATURE' | 'ALL'
  featureDescription: string
  fewShotPolicy: FewShotPolicy
  schemaVersion: '1.0'
  promptVersion: '1.0'
  scopeSelectionIds: string[]
  prompt: string
}

export interface MaterialTypeOption {
  id: string
  label: string
  documentCount: number
}

export interface ScopeVersionOption {
  id: string
  label: string
  materialTypes: MaterialTypeOption[]
}

export interface ScopeSystemOption {
  id: string
  label: string
  versions: ScopeVersionOption[]
}

export interface ScopeKnowledgeBaseOption {
  id: string
  label: string
  systems: ScopeSystemOption[]
}

export interface ScopeCatalog {
  knowledgeBases: ScopeKnowledgeBaseOption[]
}

export interface TaskCreated {
  id: string
}

export interface GenerationTaskDetail {
  id: string
  taskMode: 'FEATURE' | 'ALL'
  status: string
  totalBatches: number
  completedBatches: number
  artifactReady: boolean
  artifactId?: string
  failureSummary?: string
  frozenScope?: {
    state: 'FROZEN'
    materialCategory?: string
    admissionType?: string
    documentCount?: number
  }
  batches?: Array<{ featureId?: string, status?: string, failureSummary?: string }>
  auditRows?: Array<{
    sequence?: number
    subjectOrFeature?: string
    issueCategory?: string
    evidenceComparison?: string
  }>
  testCaseRows?: Array<{
    caseName?: string
    featureModule?: string
    preconditions?: string
    executionSteps?: string
    expectedResult?: string
    requirementContent?: string
  }>
}

export interface GenerationTaskListItem {
  id: string
  taskMode: 'FEATURE' | 'ALL'
  status: string
  createdAt?: string
  totalBatches?: number
  completedBatches?: number
  failureSummary?: string
  artifactReady: boolean
}

export interface GenerationTaskPage {
  items: GenerationTaskListItem[]
  page: number
  size: number
  totalItems: number
}

export interface TaskListQuery {
  page: number
  size: number
}

export interface TaskApi {
  getTaskOptions(refresh?: boolean): Promise<ScopeCatalog>
  createTask(payload: CreateTaskPayload): Promise<TaskCreated>
  listTasks(pagination: TaskListQuery): Promise<GenerationTaskPage>
  getTask(taskId: string): Promise<GenerationTaskDetail>
  cancelTask(taskId: string): Promise<void>
  retryTask(taskId: string): Promise<void>
  artifactDownloadUrl(artifactId: string): string
}

export function createTaskApi(fetcher: typeof fetch = fetch): TaskApi {
  return {
    async getTaskOptions(refresh = false) {
      const url = refresh ? '/api/task-options?refresh=true' : '/api/task-options'
      const response = await request<{ scopeCatalog: ScopeCatalog }>(fetcher, url)
      return response.scopeCatalog
    },
    async createTask(payload) {
      return request<TaskCreated>(fetcher, '/api/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
    },
    async listTasks(pagination) {
      const parameters = new URLSearchParams({
        page: String(pagination.page),
        size: String(pagination.size),
      })
      return request<GenerationTaskPage>(fetcher, `/api/tasks?${parameters.toString()}`)
    },
    async getTask(taskId) {
      return request<GenerationTaskDetail>(fetcher, `/api/tasks/${encodeURIComponent(taskId)}`)
    },
    async cancelTask(taskId) {
      await request<void>(fetcher, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' })
    },
    async retryTask(taskId) {
      await request<void>(fetcher, `/api/tasks/${encodeURIComponent(taskId)}/retry`, { method: 'POST' })
    },
    artifactDownloadUrl(artifactId) {
      return `/api/artifacts/${encodeURIComponent(artifactId)}/download`
    },
  }
}

async function request<T>(fetcher: typeof fetch, url: string, init?: RequestInit): Promise<T> {
  const response = await fetcher(url, init)
  if (!response.ok) {
    throw new Error(`任务请求失败（${response.status}）`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export const taskApi = createTaskApi()
