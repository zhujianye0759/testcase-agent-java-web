export type FewShotPolicy = 'AUTO' | 'NONE'

export interface CreateTaskPayload {
  taskMode: 'FEATURE' | 'ALL'
  featureDescription: string
  fewShotPolicy: FewShotPolicy
  schemaVersion: '2.0'
  promptVersion: '2.0'
  scopeSelectionIds: string[]
  prompt: string
  workflowVersion: '2.0'
  inputVersion: '2.0'
  artifactVersion: '2.0'
  approvedFunctionScope: ApprovedFunctionScopePayload
}

/** Versioned result of the independent admission review; the generation UI freezes it without rediscovery. */
export interface ApprovedFunctionScopePayload {
  scopeVersion: string
  functions: Array<{
    functionKey: string
    name: string
    path: string
    description: string
  }>
}

export interface MaterialTypeOption {
  id: string
  label: string
  documentCount: number
  /** Exact server-authorized document leaves. Omitted by an older backend, which keeps legacy type selection usable. */
  documents?: MaterialDocumentOption[]
}

export interface MaterialDocumentOption {
  id: string
  label: string
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
  workflowVersion: '1.0' | '2.0'
  processingStatus: string
  coverageStatus: string
  retryEligibility?: {
    canRetry: boolean
    unavailableReason: string
  }
  pendingCandidateCaseCount: number
  /** Reader-safe candidate audit; absent for legacy structured responses that predate candidate protocol V1. */
  functionCandidateSummary?: {
    acceptedCandidateCount: number
    pendingCandidateCount: number
    rejectedCandidateCount: number
    noFunctionSourceCount: number
    unresolvedSourceCount: number
    incompleteWindowCount: number
    issues: Array<{
      subject: string
      status: string
      description: string
      missingInformation: string[]
    }>
  }
  phaseProgress: {
    materialTraversal: StructuredPhaseCount
    factExtraction: StructuredPhaseCount
    requirementReview: StructuredPhaseCount
    featureReconciliation: StructuredPhaseCount
    testcaseDesign: StructuredPhaseCount
  }
  reviewFindings: Array<{
    sourceLabel: string
    subject: string
    issueType: string
    description: string
    handlingLevel: string
    affectedScope: string
    badSourceExample: string
    proposedGoodExample: string
    testDesignImpact: string
    currentProjectRecommendation: string
    designCenterGuidelineRecommendation: string
  }>
  testabilityFeedback: Array<{
    functionName: string
    observationType: string
    description: string
    affectedFactTypes: string[]
  }>
  reconciliations: Array<{
    functionListPaths: string[]
    requirementFunctions: string[]
    classification: string
    scopeRecommendation: string
    confirmationStatus: string
  }>
  testPoints: Array<{
    functionName: string
    type: string
    description: string
    basis: string
    generationOutcome: string
    generationMissingInformation: string[]
    missingInformation: string[]
    formalCoverageSatisfied: boolean
    testcases: Array<{
      name: string
      title: string
      priority: string
      status: string
      preconditions: string[]
      initialization: {
        hardwareConfiguration: string[]
        softwareConfiguration: string[]
        testConfiguration: string[]
        parameterConfiguration: string[]
      }
      inputs: Array<{
        content: string
        nature: string
        source: string
        method: string
        authenticity: string
        sequence: string
      }>
      steps: Array<{
        stepNo: number
        action: string
        expected: string
        evaluationCriteria: string
        terminationOrError: string
        resultCollection: string
      }>
      expectedResults: string[]
      evaluationCriteria: string
      resultEvaluationCriteria: string
      terminationConditions: string[]
      resultCollection: string
      authoringInformation: { author: string, date: string }
      requirementSummaries: string[]
      missingInformation: string[]
    }>
  }>
  /** V2-only bounded collections. V1 responses intentionally omit this field. */
  v2Collections?: {
    testabilityFeedback: StructuredDetailPage<StructuredGenerationResult['testabilityFeedback'][number]>
    testPoints: StructuredDetailPage<StructuredGenerationResult['testPoints'][number]>
    testcases: StructuredDetailPage<StructuredGenerationResult['testPoints'][number]['testcases'][number]>
  }
}

export interface StructuredDetailPage<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  hasNext: boolean
}

export interface StructuredDetailQuery {
  feedbackPage: number
  testPointPage: number
  testcasePage: number
  size: number
}

export interface StructuredPhaseCount {
  total: number
  completed: number
  failed: number
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
  getTask(taskId: string, query?: StructuredDetailQuery): Promise<GenerationTaskDetail>
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
    async getTask(taskId, query) {
      const parameters = query ? `?${new URLSearchParams({
        feedbackPage: String(query.feedbackPage),
        testPointPage: String(query.testPointPage),
        testcasePage: String(query.testcasePage),
        size: String(query.size),
      }).toString()}` : ''
      return request<GenerationTaskDetail>(fetcher, `/api/tasks/${encodeURIComponent(taskId)}${parameters}`)
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
