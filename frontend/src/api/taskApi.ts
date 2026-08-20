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

/** Aggregate-only progress used by the detail page; it deliberately excludes technical identifiers and raw model output. */
export interface GenerationTaskBusinessProgress {
  currentBusinessStage: string
  materialDocumentTotal: number
  completeMaterialDocumentCount: number
  materialUnitTotal: number
  processedMaterialUnitCount: number
  totalAuditWork: number
  completedAuditWork: number
  failedAuditWork: number
  featureCandidateTotal: number
  functionListMissingCount: number
  requirementMissingCount: number
  conflictCount: number
  splitCount: number
  mergeCount: number
  insufficientEvidenceCount: number
  frozenComplete: boolean
  frozenFeatureTotal: number | null
  generationEligibleFrozenFeatureCount: number | null
  generationIneligibleFrozenFeatureCount: number | null
  expectedTestCaseTotal: number | null
  acceptedTestCaseCount: number
  coverageStatus: string
  businessReason: string
}

/** Java-validated and persisted business projection; it contains no KEE/model payload or internal keys. */
export interface StructuredGenerationResult {
  processingStatus: string
  coverageStatus: 'PENDING' | 'SATISFIED' | 'INSUFFICIENT'
  pendingCandidateCaseCount: number
  reviewFindings: Array<{
    sourceLabel: string
    subject: string
    issueType: string
    description: string
    handlingLevel: 'BLOCKING' | 'CONTINUE_INCOMPLETE' | 'IMPROVEMENT'
    testDesignImpact: string
    currentProjectRecommendation: string
    designCenterGuidelineRecommendation: string
  }>
  reconciliations: Array<{
    functionListPaths: string[]
    requirementFunctions: string[]
    classification: string
    scopeRecommendation: string
    confirmationStatus: 'CONFIRMED' | 'PENDING_CONFIRMATION'
  }>
  testPoints: Array<{
    functionName: string
    type: string
    description: string
    basis: 'FORMAL_REQUIREMENT' | 'GENERAL_EXPERIENCE'
    missingInformation: string[]
    formalCoverageSatisfied: boolean
    testcases: Array<{
      title: string
      status: 'FORMAL' | 'PENDING_CONFIRMATION'
      preconditions: string[]
      steps: Array<{ stepNo: number, action: string, expected: string }>
      requirementSummaries: string[]
      missingInformation: string[]
    }>
  }>
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
  structuredResult?: StructuredGenerationResult
  businessProgress: GenerationTaskBusinessProgress
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
