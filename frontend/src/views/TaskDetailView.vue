<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { taskApi, type GenerationTaskDetail, type StructuredDetailQuery } from '../api/taskApi'

const props = withDefaults(defineProps<{
  taskId: string
  getTask?: (taskId: string, query?: StructuredDetailQuery) => Promise<GenerationTaskDetail>
  cancelTask?: (taskId: string) => Promise<void>
  retryTask?: (taskId: string) => Promise<void>
}>(), {
  getTask: (taskId: string) => taskApi.getTask(taskId),
  cancelTask: (taskId: string) => taskApi.cancelTask(taskId),
  retryTask: (taskId: string) => taskApi.retryTask(taskId),
})

const detail = ref<GenerationTaskDetail>()
const feedbackPage = ref(0)
const testPointPage = ref(0)
const testcasePage = ref(0)
const structuredPageSize = 10
const loading = ref(true)
const loadError = ref('')
const loadErrorState = computed(() => {
  if (/（403）|\b403\b/.test(loadError.value)) return 'forbidden'
  if (/（404）|\b404\b/.test(loadError.value)) return 'not-found'
  return 'error'
})
const pendingAction = ref<'cancel' | 'retry'>()
const actionError = ref('')
const mutating = ref(false)
let loadSequence = 0
const confirmationDialog = ref<{ showModal?: () => void, close?: () => void }>()
const actionTrigger = ref<{ focus: () => void }>()
const downloadUrl = computed(() => {
  if (!detail.value?.artifactReady || !detail.value.artifactId) return ''
  return taskApi.artifactDownloadUrl(detail.value.artifactId)
})
const canCancel = computed(() => isV2Structured.value
  && detail.value !== undefined && !['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(detail.value.status))
const hasFailedBatch = computed(() => detail.value?.batches?.some((batch) => batch.status === 'FAILED') ?? false)
const structuredResult = computed(() => detail.value?.structuredResult)
const isV2Structured = computed(() => structuredResult.value?.workflowVersion === '2.0')
const isStructuredRetry = computed(() => structuredResult.value !== undefined)
const isDiscoveryRetry = computed(() => !isStructuredRetry.value
  && detail.value?.status === 'FAILED' && !hasFailedBatch.value)
const canRetry = computed(() => {
  if (!detail.value) return false
  return isV2Structured.value && structuredResult.value?.retryEligibility?.canRetry === true
})
const displayedFeedback = computed(() => isV2Structured.value
  ? structuredResult.value?.v2Collections?.testabilityFeedback.items
    ?? structuredResult.value?.testabilityFeedback ?? []
  : structuredResult.value?.testabilityFeedback ?? [])
const displayedTestPoints = computed(() => isV2Structured.value
  ? structuredResult.value?.v2Collections?.testPoints.items
    ?? structuredResult.value?.testPoints ?? []
  : structuredResult.value?.testPoints ?? [])
const feedbackPageResult = computed(() => structuredResult.value?.v2Collections?.testabilityFeedback)
const testPointPageResult = computed(() => structuredResult.value?.v2Collections?.testPoints)
const testcasePageResult = computed(() => structuredResult.value?.v2Collections?.testcases)
const showPendingCandidateCaseCount = computed(() => !isV2Structured.value
  || ['COMPLETED', 'FAILED', 'CANCELLED', '已完成', '失败', '已取消']
    .includes(structuredResult.value?.processingStatus ?? ''))
const batchFailureSummary = computed(() => detail.value?.batches
  ?.filter((batch) => batch.status === 'FAILED' && batch.failureSummary)
  .map((batch) => batch.failureSummary)
  .join('；') ?? '')
const businessProgress = computed(() => detail.value?.businessProgress)
const functionCandidateSummary = computed(() => {
  const summary = structuredResult.value?.functionCandidateSummary
  if (!summary) return undefined
  const hasCandidateAudit = summary.acceptedCandidateCount + summary.pendingCandidateCount
    + summary.rejectedCandidateCount + summary.noFunctionSourceCount + summary.unresolvedSourceCount
    + summary.incompleteWindowCount + summary.issues.length > 0
  return hasCandidateAudit ? summary : undefined
})
const structuredStages = computed(() => {
  const result = structuredResult.value
  if (!result) return []
  const phases = isV2Structured.value
    ? [
        { label: '材料遍历', detail: structuredPhaseDetail(result.phaseProgress.materialTraversal, '份材料'), count: result.phaseProgress.materialTraversal },
        { label: '需求事实提取', detail: structuredPhaseDetail(result.phaseProgress.factExtraction, '项工作'), count: result.phaseProgress.factExtraction },
        { label: '测试用例设计', detail: structuredPhaseDetail(result.phaseProgress.testcaseDesign, '项工作'), count: result.phaseProgress.testcaseDesign },
      ]
    : [
        { label: '材料遍历', detail: structuredPhaseDetail(result.phaseProgress.materialTraversal, '份材料'), count: result.phaseProgress.materialTraversal },
        { label: '需求材料审查', detail: structuredPhaseDetail(result.phaseProgress.requirementReview, '项工作'), count: result.phaseProgress.requirementReview },
        { label: '功能清单核对', detail: structuredPhaseDetail(result.phaseProgress.featureReconciliation, '项工作'), count: result.phaseProgress.featureReconciliation },
        { label: '测试用例设计', detail: structuredPhaseDetail(result.phaseProgress.testcaseDesign, '项工作'), count: result.phaseProgress.testcaseDesign },
      ]
  const activeIndex = isStructuredCompleted(result.processingStatus)
    ? phases.length
    : phases.findIndex(phase => phase.count.failed > 0 || phase.count.total === 0 || phase.count.completed < phase.count.total)
  const currentIndex = activeIndex < 0 ? phases.length : activeIndex
  return phases.map((phase, index) => {
    const state = phase.count.failed > 0
      ? 'failed'
      : index < currentIndex || isStructuredCompleted(result.processingStatus)
        ? 'complete'
        : index === currentIndex
          ? isStructuredCancelled(result.processingStatus) ? 'cancelled' : 'current'
          : 'pending'
    return {
      ...phase,
      state,
      detail: state === 'cancelled' ? `${phase.detail}，处理已停止` : phase.detail,
    }
  })
})
const businessStages = computed(() => {
  const progress = businessProgress.value
  if (!detail.value || !progress) return []
  const activeStage = activeBusinessStage(detail.value.status, progress.currentBusinessStage, progress.frozenComplete)
  return [
    { label: '材料清单', detail: `${progress.completeMaterialDocumentCount} / ${progress.materialDocumentTotal} 份材料`, index: 0 },
    { label: '双向审查', detail: `${progress.completedAuditWork} / ${progress.totalAuditWork} 项审查工作`, index: 1 },
    { label: '功能冻结', detail: progress.frozenComplete ? '已完成冻结' : '尚未冻结', index: 2 },
    { label: '测试用例生成', detail: progress.frozenComplete
      ? `${progress.acceptedTestCaseCount} / ${progress.expectedTestCaseTotal ?? 0} 条已接收`
      : '等待功能冻结', index: 3 },
    { label: '交付结果', detail: deliveryStatusText(detail.value.status), index: 4 },
  ].map((stage) => ({
    ...stage,
    state: stage.index < activeStage ? 'complete' : stage.index === activeStage ? 'current' : 'pending',
  }))
})

async function loadTask(withStructuredPaging = false) {
  const requestTaskId = props.taskId
  const requestSequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const loadedDetail = await props.getTask(requestTaskId, withStructuredPaging ? {
      feedbackPage: feedbackPage.value,
      testPointPage: testPointPage.value,
      testcasePage: testcasePage.value,
      size: structuredPageSize,
    } : undefined)
    if (!isCurrentLoad(requestSequence, requestTaskId)) return
    detail.value = loadedDetail
  } catch (error) {
    if (!isCurrentLoad(requestSequence, requestTaskId)) return
    loadError.value = error instanceof Error ? error.message : '任务详情加载失败，请稍后重试。'
  } finally {
    if (isCurrentLoad(requestSequence, requestTaskId)) loading.value = false
  }
}

async function changeStructuredPage(collection: 'feedback' | 'testPoints' | 'testcases', page: number) {
  if (page < 0 || loading.value) return
  if (collection === 'feedback') feedbackPage.value = page
  else if (collection === 'testPoints') {
    testPointPage.value = page
    testcasePage.value = 0
  } else testcasePage.value = page
  await loadTask(true)
}

function testcasesForPoint(point: NonNullable<typeof displayedTestPoints.value>[number]) {
  return isV2Structured.value ? testcasePageResult.value?.items ?? [] : point.testcases
}

function isCurrentLoad(requestSequence: number, requestTaskId: string) {
  return requestSequence === loadSequence && requestTaskId === props.taskId
}

function valueOrDash(value?: string | number) {
  return value === undefined || value === null || value === '' ? '-' : String(value)
}

function statusText(status?: string) {
  return {
    QUEUED: '排队中',
    AUDITING: '审查中',
    GENERATING: '生成中',
    VALIDATING: '校验中',
    ACCEPTED: '已接受',
    FAILED: '生成失败',
    CANCELLED: '已取消',
    COMPLETED: '已完成',
    PARTIAL: '部分完成',
  }[status ?? ''] ?? valueOrDash(status)
}

function activeBusinessStage(status: string, currentBusinessStage: string, frozenComplete: boolean) {
  if (['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status)) return 4
  if (['GENERATING', 'VALIDATING'].includes(status)) return 3
  if (frozenComplete || currentBusinessStage === '功能冻结') return 2
  if (currentBusinessStage === '材料清单' || status === 'QUEUED') return 0
  return 1
}

function deliveryStatusText(status: string) {
  return {
    COMPLETED: '完整交付',
    PARTIAL: '审查已完成，测试用例不完整',
    FAILED: '材料或审查失败',
    CANCELLED: '任务已取消',
  }[status] ?? '处理中'
}

function materialCategoryText(category?: string) {
  return {
    admission_material: '准入材料',
    version_common: '版本公共材料',
    test_process: '测试过程材料',
  }[category ?? ''] ?? valueOrDash(category)
}

function admissionTypeText(types?: string) {
  if (!types) return '-'
  const labels: Record<string, string> = {
    requirements_spec: '需求规格说明',
    work_order_plan: '工单方案',
    prototype: '界面原型图',
    requirement_list: '需求清单',
    function_list: '功能清单',
    interface_list: '接口清单',
    detailed_design: '详细设计',
    environment_config: '环境配置',
  }
  return types.split(/[,，]/).map((type) => labels[type.trim()] ?? type.trim()).join('、')
}

function structuredPhaseDetail(phase: { total: number, completed: number, failed: number }, unit: string) {
  if (phase.total === 0) {
    return isStructuredCompleted(structuredResult.value?.processingStatus) ? '无已登记工作' : '等待登记'
  }
  return `${phase.completed} / ${phase.total} ${unit}${phase.failed ? `，${phase.failed} 项失败` : ''}`
}

function structuredProgressText() {
  const result = structuredResult.value
  return result ? `结构化流程${structuredProcessingText(result.processingStatus)}` : ''
}

function structuredDeliveryStatusText() {
  const result = structuredResult.value
  if (!result) return '交付状态不可用'
  if (['PENDING', '待处理'].includes(result.processingStatus)) return '等待处理'
  if (['RUNNING', '处理中'].includes(result.processingStatus)) return '处理中，覆盖结果待定'
  if (['FAILED', '失败'].includes(result.processingStatus)) {
    return detail.value?.artifactReady ? '处理存在失败，已保存可用结果' : '处理失败，未形成交付'
  }
  if (isStructuredCancelled(result.processingStatus)) return '处理已取消'
  if (!isStructuredCompleted(result.processingStatus)) return '交付状态不可用'
  const coverage = coverageText(result.coverageStatus)
  return coverage === '覆盖状态不可用' ? '交付状态不可用' : `处理已完成，${coverage}`
}

function downloadResultText() {
  if (!detail.value?.artifactReady) return '结果生成后可下载'
  if (!structuredResult.value) return detail.value.status === 'PARTIAL' ? '可下载已完成部分' : '已生成，可下载'
  return isCoverageComplete(structuredResult.value.coverageStatus)
    ? 'Excel 已生成，正式覆盖完整'
    : `Excel 已生成，${coverageText(structuredResult.value.coverageStatus)}`
}

function coverageText(status?: string) {
  const mapped = {
    PENDING: '正式覆盖待完成',
    COMPLETE: '正式覆盖完整',
    PARTIAL: '正式覆盖部分完整',
    UNABLE_TO_GENERATE: '正式覆盖无法生成',
  }[status ?? ''] ?? '覆盖状态不可用'
  return ['正式覆盖待完成', '正式覆盖完整', '正式覆盖部分完整', '正式覆盖无法生成'].includes(status ?? '')
    ? status as string
    : mapped
}

function structuredProcessingText(status?: string) {
  const mapped = {
    PENDING: '待处理',
    RUNNING: '处理中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }[status ?? ''] ?? '处理状态不可用'
  return ['待处理', '处理中', '已完成', '失败', '已取消'].includes(status ?? '') ? status as string : mapped
}

function isStructuredCompleted(status?: string) {
  return status === 'COMPLETED' || status === '已完成'
}

function isStructuredCancelled(status?: string) {
  return status === 'CANCELLED' || status === '已取消'
}

function isCoverageComplete(status?: string) {
  return status === 'COMPLETE' || status === '正式覆盖完整'
}

function structuredTestcaseEmptyText() {
  const result = structuredResult.value
  if (isStructuredCompleted(result?.processingStatus)
      && ['UNABLE_TO_GENERATE', '正式覆盖无法生成'].includes(result?.coverageStatus ?? '')) {
    return '处理已完成，但没有可用于生成正式用例的已确认功能范围，因此未生成测试用例。'
  }
  return isStructuredCompleted(result?.processingStatus)
    ? '处理已完成，当前没有已验证并保存的测试用例。'
    : '尚无已验证并保存的测试用例。'
}

function candidateStatusText(status?: string) {
  if (status === 'FORMAL' || status === '正式用例') return '正式用例'
  if (status === 'PENDING_CONFIRMATION' || status === '待确认用例') return '待确认候选'
  return '用例状态不可用'
}

function handlingLevelText(level?: string) {
  const mapped = {
    BLOCKING: '阻断',
    CONTINUE_INCOMPLETE: '继续执行但信息不完整',
    IMPROVEMENT: '改进建议',
  }[level ?? ''] ?? '严重程度不可用'
  return ['阻断', '继续执行但信息不完整', '改进建议'].includes(level ?? '') ? level as string : mapped
}

function structuredInputText(input: {
  content: string
  nature: string
  source: string
  method: string
  authenticity: string
  sequence?: string
}) {
  const sequence = input.sequence ? `；顺序：${input.sequence}` : ''
  return `${input.content}（性质：${input.nature}；来源：${input.source}；方法：${input.method}；真实性：${input.authenticity}${sequence}）`
}

function confirmationText(status?: string) {
  if (status === 'CONFIRMED' || status === '已确认') return '已确认'
  if (status === 'PENDING_CONFIRMATION' || status === '待确认') return '待确认'
  return '确认状态不可用'
}

function reconciliationClassificationText(classification?: string) {
  const mapped = {
    EXACT_MATCH: '精确匹配',
    FUNCTION_LIST_ONLY: '仅功能清单存在',
    REQUIREMENTS_ONLY: '仅需求材料存在',
    CONFLICT: '内容冲突',
    DUPLICATE: '重复功能',
    SPLIT: '需要拆分',
    MERGE: '需要合并',
    INSUFFICIENT_EVIDENCE: '证据不足',
  }[classification ?? ''] ?? '核对结论不可用'
  return ['精确匹配', '完全一致', '仅功能清单存在', '仅需求材料存在', '内容冲突', '范围冲突',
    '重复功能', '需要拆分', '建议拆分', '需要合并', '建议合并', '证据不足'].includes(classification ?? '')
    ? classification as string
    : mapped
}

function testPointTypeText(type?: string) {
  const mapped = {
    NORMAL_BEHAVIOR: '正常行为',
    INPUT_VALIDATION: '输入校验',
    BOUNDARY_VALUE: '边界值',
    PERMISSION: '权限',
    STATE_TRANSITION: '状态转换',
    BUSINESS_EXCEPTION: '业务异常',
    DEPENDENCY_FAILURE: '依赖失败',
  }[type ?? '']
  const readerSafeV2Types = new Set(['正常行为', '输入校验', '边界值', '权限', '状态转换', '业务异常', '依赖失败'])
  return mapped ?? (type && readerSafeV2Types.has(type) ? type : '测试点类型不可用')
}

function openConfirmation(action: 'cancel' | 'retry', event: { currentTarget: unknown }) {
  pendingAction.value = action
  actionError.value = ''
  actionTrigger.value = canFocus(event.currentTarget) ? event.currentTarget : undefined
  void nextTick(() => confirmationDialog.value?.showModal?.())
}

function canFocus(value: unknown): value is { focus: () => void } {
  return typeof value === 'object' && value !== null && 'focus' in value && typeof value.focus === 'function'
}

function closeConfirmation() {
  confirmationDialog.value?.close?.()
  pendingAction.value = undefined
  actionError.value = ''
  void nextTick(() => actionTrigger.value?.focus())
}

async function confirmAction() {
  if (!pendingAction.value || mutating.value) return

  mutating.value = true
  actionError.value = ''
  try {
    if (pendingAction.value === 'cancel') {
      await props.cancelTask(props.taskId)
    } else {
      await props.retryTask(props.taskId)
    }
    closeConfirmation()
    await loadTask()
  } catch (error) {
    const message = error instanceof Error ? error.message : '操作未完成，请稍后重试。'
    if (pendingAction.value === 'retry' && /（409）|\b409\b/.test(message)) {
      confirmationDialog.value?.close?.()
      pendingAction.value = undefined
      await loadTask()
    } else {
      actionError.value = message
    }
  } finally {
    mutating.value = false
  }
}

onMounted(loadTask)
watch(() => props.taskId, () => {
  feedbackPage.value = 0
  testPointPage.value = 0
  void loadTask()
})
</script>

<template>
  <!-- [Req-ID]: REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008, REQ-CWR-001, REQ-CWR-002, REQ-FTG-007, REQ-FTG-009, REQ-ESR-004 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-005, REQ-UIX-006, REQ-UIX-007 -->
  <section
    class="task-detail"
    aria-labelledby="task-detail-title"
  >
    <header class="task-detail__heading">
      <div>
        <p class="page-eyebrow">
          TASK INTELLIGENCE
        </p>
        <h1 id="task-detail-title">
          生成任务详情
        </h1>
        <p>查看当前任务的进度、审查发现、测试用例和下载结果。</p>
      </div>
      <p class="task-detail__heading-note">
        材料范围已锁定<br>结果可追溯
      </p>
    </header>

    <p
      v-if="loading"
      role="status"
      aria-live="polite"
    >
      正在加载任务详情…
    </p>
    <div
      v-else-if="loadError"
      class="task-detail__error"
      :data-state="loadErrorState"
      role="alert"
    >
      <p>{{ loadError }}</p>
      <button
        type="button"
        @click="loadTask()"
      >
        重试加载
      </button>
    </div>
    <div
      v-else-if="detail"
      data-state="ready"
    >
      <dl class="task-detail__summary">
        <div>
          <dt>状态</dt>
          <dd
            class="status-chip"
            :aria-label="`状态：${statusText(detail.status)}`"
          >
            {{ statusText(detail.status) }}
          </dd>
        </div>
        <div>
          <dt>进度</dt>
          <dd>{{ structuredResult ? structuredProgressText() : `${detail.completedBatches} / ${detail.totalBatches} 个批次已完成` }}</dd>
        </div>
        <div>
          <dt>下载结果</dt>
          <dd>{{ downloadResultText() }}</dd>
        </div>
        <div v-if="structuredResult">
          <dt>处理状态</dt>
          <dd>{{ structuredProcessingText(structuredResult.processingStatus) }}</dd>
        </div>
        <div v-if="structuredResult">
          <dt>正式覆盖</dt>
          <dd>{{ coverageText(structuredResult.coverageStatus) }}</dd>
        </div>
        <div v-if="structuredResult && showPendingCandidateCaseCount">
          <dt>待确认用例</dt>
          <dd>{{ structuredResult.pendingCandidateCaseCount }} 条</dd>
        </div>
      </dl>

      <section
        v-if="structuredResult"
        class="task-detail__section task-detail__business-progress"
        aria-labelledby="structured-progress-title"
        data-testid="structured-progress"
      >
        <div class="task-detail__progress-heading">
          <div>
            <h2 id="structured-progress-title">
              结构化处理进度
            </h2>
            <p>各阶段数据均来自已保存的结构化工作记录。</p>
          </div>
          <p
            class="status-chip"
            :aria-label="`交付状态：${structuredDeliveryStatusText()}`"
            data-testid="delivery-status"
          >
            {{ structuredDeliveryStatusText() }}
          </p>
        </div>
        <ol
          class="task-detail__stage-flow task-detail__stage-flow--structured"
          aria-label="结构化任务处理流程"
        >
          <li
            v-for="stage in structuredStages"
            :key="stage.label"
            :class="`task-detail__stage task-detail__stage--${stage.state}`"
            :aria-current="stage.state === 'current' ? 'step' : undefined"
          >
            <strong>{{ stage.label }}</strong>
            <span>{{ stage.detail }}</span>
          </li>
        </ol>
      </section>

      <section
        v-else-if="businessProgress"
        class="task-detail__section task-detail__business-progress"
        aria-labelledby="business-progress-title"
        data-testid="business-progress"
      >
        <div class="task-detail__progress-heading">
          <div>
            <h2 id="business-progress-title">
              业务进度
            </h2>
            <p>当前阶段：{{ businessProgress.currentBusinessStage }}</p>
          </div>
          <p
            class="status-chip"
            :aria-label="`交付状态：${deliveryStatusText(detail.status)}`"
            data-testid="delivery-status"
          >
            {{ deliveryStatusText(detail.status) }}
          </p>
        </div>
        <ol
          class="task-detail__stage-flow"
          aria-label="任务业务流程"
        >
          <li
            v-for="stage in businessStages"
            :key="stage.label"
            :class="`task-detail__stage task-detail__stage--${stage.state}`"
            :aria-current="stage.state === 'current' ? 'step' : undefined"
          >
            <strong>{{ stage.label }}</strong>
            <span>{{ stage.detail }}</span>
          </li>
        </ol>
        <dl class="task-detail__description-list task-detail__progress-counts">
          <div><dt>材料处理</dt><dd>{{ businessProgress.completeMaterialDocumentCount }} / {{ businessProgress.materialDocumentTotal }} 份；{{ businessProgress.processedMaterialUnitCount }} / {{ businessProgress.materialUnitTotal }} 个材料单元</dd></div>
          <div><dt>审查进度</dt><dd>{{ businessProgress.completedAuditWork }} / {{ businessProgress.totalAuditWork }} 项审查工作<span v-if="businessProgress.failedAuditWork">，{{ businessProgress.failedAuditWork }} 项未完成</span></dd></div>
          <div><dt>审查候选</dt><dd>审查候选 {{ businessProgress.featureCandidateTotal }} 项（尚非最终功能数）</dd></div>
          <div><dt>审查发现</dt><dd>功能清单缺失 {{ businessProgress.functionListMissingCount }} 项；需求缺失 {{ businessProgress.requirementMissingCount }} 项；冲突 {{ businessProgress.conflictCount }} 项；拆分 {{ businessProgress.splitCount }} 项；合并 {{ businessProgress.mergeCount }} 项；证据不足 {{ businessProgress.insufficientEvidenceCount }} 项</dd></div>
          <div>
            <dt>功能冻结</dt>
            <dd v-if="businessProgress.frozenComplete">
              已冻结 {{ businessProgress.frozenFeatureTotal }} 个功能，其中 {{ businessProgress.generationEligibleFrozenFeatureCount }} 个可生成，{{ businessProgress.generationIneligibleFrozenFeatureCount }} 个暂不生成
            </dd>
            <dd v-else>
              尚未冻结
            </dd>
          </div>
          <div>
            <dt>测试用例</dt>
            <dd v-if="businessProgress.frozenComplete">
              {{ businessProgress.acceptedTestCaseCount }} / {{ businessProgress.expectedTestCaseTotal }} 条已接收
            </dd>
            <dd v-else>
              功能冻结后计算测试用例目标
            </dd>
          </div>
        </dl>
        <p class="task-detail__business-reason">
          {{ businessProgress.businessReason }}
        </p>
      </section>

      <div
        v-if="canCancel || canRetry"
        class="task-detail__actions"
        aria-label="任务操作"
      >
        <button
          v-if="canCancel"
          type="button"
          class="task-detail__cancel-action"
          :disabled="mutating"
          @click="openConfirmation('cancel', $event)"
        >
          取消任务
        </button>
        <button
          v-if="canRetry"
          type="button"
          :disabled="mutating"
          @click="openConfirmation('retry', $event)"
        >
          {{ isStructuredRetry ? '重试失败的结构化处理' : isDiscoveryRetry ? '重新识别并生成' : '重试失败批次' }}
        </button>
      </div>
      <p
        v-if="structuredResult && !canRetry && structuredResult.retryEligibility?.unavailableReason"
        class="task-detail__business-reason"
      >
        {{ structuredResult.retryEligibility.unavailableReason }}
      </p>

      <section
        class="task-detail__section"
        aria-labelledby="frozen-scope-title"
      >
        <h2 id="frozen-scope-title">
          本次材料范围
        </h2>
        <dl class="task-detail__description-list">
          <div><dt>材料类别</dt><dd>{{ materialCategoryText(detail.frozenScope?.materialCategory) }}</dd></div>
          <div><dt>准入材料类型</dt><dd>{{ admissionTypeText(detail.frozenScope?.admissionType) }}</dd></div>
          <div><dt>已锁定材料</dt><dd>{{ detail.frozenScope?.documentCount ? `${detail.frozenScope.documentCount} 份` : '-' }}</dd></div>
        </dl>
      </section>

      <section
        v-if="structuredResult"
        class="task-detail__section"
        aria-labelledby="structured-review-title"
        data-testid="structured-result"
      >
        <!-- [Req-ID]: REQ-TGV2-009, REQ-TGV2-010 -->
        <h2 id="structured-review-title">
          {{ isV2Structured ? '需求可测性反馈' : '需求与功能清单审查发现' }}
        </h2>
        <div
          v-if="isV2Structured && displayedFeedback.length"
          class="task-detail__cards"
          data-testid="testability-feedback"
        >
          <article
            v-for="feedback in displayedFeedback"
            :key="`${feedback.functionName}-${feedback.observationType}-${feedback.description}`"
          >
            <h3>{{ feedback.functionName }}</h3>
            <p>{{ feedback.observationType }}</p>
            <p>{{ feedback.description }}</p>
            <p v-if="feedback.affectedFactTypes.length">
              影响的需求事实：{{ feedback.affectedFactTypes.join('、') }}
            </p>
          </article>
        </div>
        <nav
          v-if="isV2Structured && feedbackPageResult && feedbackPageResult.totalItems > feedbackPageResult.size"
          class="task-detail__pagination"
          aria-label="需求可测性反馈分页"
        >
          <button
            type="button"
            :disabled="loading || feedbackPageResult.page === 0"
            @click="changeStructuredPage('feedback', feedbackPageResult.page - 1)"
          >
            上一页
          </button>
          <span>第 {{ feedbackPageResult.page + 1 }} 页，共 {{ feedbackPageResult.totalItems }} 条</span>
          <button
            type="button"
            :disabled="loading || !feedbackPageResult.hasNext"
            @click="changeStructuredPage('feedback', feedbackPageResult.page + 1)"
          >
            下一页
          </button>
        </nav>
        <p
          v-else-if="isV2Structured"
          data-state="no-results"
        >
          当前没有已保存的需求可测性反馈。
        </p>
        <!-- [Req-ID]: REQ-AFCE-006, REQ-AFCE-008 -->
        <div
          v-if="!isV2Structured && functionCandidateSummary"
          class="task-detail__candidate-audit"
          data-testid="function-candidate-summary"
        >
          <h3>功能候选审查</h3>
          <dl class="task-detail__description-list task-detail__progress-counts">
            <div><dt>正式接受</dt><dd>正式接受 {{ functionCandidateSummary.acceptedCandidateCount }} 条</dd></div>
            <div><dt>待确认</dt><dd>待确认 {{ functionCandidateSummary.pendingCandidateCount }} 条</dd></div>
            <div><dt>未纳入</dt><dd>未纳入 {{ functionCandidateSummary.rejectedCandidateCount }} 条</dd></div>
            <div><dt>原文无功能</dt><dd>原文无功能 {{ functionCandidateSummary.noFunctionSourceCount }} 条</dd></div>
            <div><dt>原文待确认</dt><dd>原文待确认 {{ functionCandidateSummary.unresolvedSourceCount }} 条</dd></div>
            <div><dt>未完成窗口</dt><dd>未完成窗口 {{ functionCandidateSummary.incompleteWindowCount }} 个</dd></div>
          </dl>
          <p class="task-detail__business-reason">
            只有正式接受的候选计入正式覆盖；待确认、未纳入和未完成范围只作为审查缺口展示。
          </p>
          <div
            v-if="functionCandidateSummary.issues.length"
            class="task-detail__cards"
          >
            <article
              v-for="issue in functionCandidateSummary.issues"
              :key="`${issue.subject}-${issue.status}-${issue.description}`"
            >
              <h4>{{ issue.subject }}</h4>
              <p>{{ issue.status }}</p>
              <p>{{ issue.description }}</p>
              <p v-if="issue.missingInformation.length">
                缺失信息：{{ issue.missingInformation.join('；') }}
              </p>
            </article>
          </div>
          <p
            v-else
            data-state="no-results"
          >
            当前没有待处理的功能候选或原文缺口。
          </p>
        </div>
        <div
          v-if="!isV2Structured && (structuredResult.reviewFindings.length || structuredResult.reconciliations.length)"
          class="task-detail__cards"
        >
          <article
            v-for="finding in structuredResult.reviewFindings"
            :key="`${finding.sourceLabel}-${finding.subject}-${finding.issueType}-${finding.description}`"
          >
            <h3>{{ finding.subject }}</h3>
            <p>{{ finding.sourceLabel }} · {{ finding.issueType }}</p>
            <p>{{ finding.description }}</p>
            <details>
              <summary>查看影响与建议</summary>
              <dl class="task-detail__result-details">
                <div v-if="finding.affectedScope">
                  <dt>影响范围</dt><dd>{{ finding.affectedScope }}</dd>
                </div>
                <div v-if="finding.badSourceExample">
                  <dt>实际坏例</dt><dd>{{ finding.badSourceExample }}</dd>
                </div>
                <div v-if="finding.proposedGoodExample">
                  <dt>建议好例（待需求方确认）</dt><dd>{{ finding.proposedGoodExample }}</dd>
                </div>
                <div><dt>测试设计影响</dt><dd>{{ finding.testDesignImpact }}</dd></div>
                <div><dt>当前项目建议</dt><dd>{{ finding.currentProjectRecommendation }}</dd></div>
                <div><dt>设计中心建议</dt><dd>{{ finding.designCenterGuidelineRecommendation }}</dd></div>
                <div><dt>严重程度</dt><dd>{{ handlingLevelText(finding.handlingLevel) }}</dd></div>
              </dl>
            </details>
          </article>
          <article
            v-for="reconciliation in structuredResult.reconciliations"
            :key="`${reconciliation.classification}-${reconciliation.functionListPaths.join('/')}-${reconciliation.requirementFunctions.join('/')}`"
          >
            <h3>{{ reconciliation.functionListPaths.join('、') || reconciliation.requirementFunctions.join('、') }}</h3>
            <p>核对结论：{{ reconciliationClassificationText(reconciliation.classification) }} · {{ confirmationText(reconciliation.confirmationStatus) }}</p>
            <p>{{ reconciliation.scopeRecommendation }}</p>
          </article>
        </div>
        <p
          v-else-if="!isV2Structured"
          data-state="no-results"
        >
          当前没有已保存的审查发现或功能核对结果。
        </p>
      </section>

      <section
        v-if="structuredResult"
        class="task-detail__section"
        aria-labelledby="structured-case-title"
      >
        <h2 id="structured-case-title">
          测试用例
        </h2>
        <div
          v-if="displayedTestPoints.length"
          class="task-detail__cards"
        >
          <article
            v-for="point in displayedTestPoints"
            :key="`${point.functionName}-${point.type}-${point.description}`"
          >
            <h3>{{ point.functionName }} · {{ point.description }}</h3>
            <p>测试点类型：{{ testPointTypeText(point.type) }}；正式覆盖：{{ point.formalCoverageSatisfied ? '已满足' : '未满足' }}</p>
            <p v-if="isV2Structured">
              生成结果：{{ point.generationOutcome }}
            </p>
            <p v-if="isV2Structured && point.generationMissingInformation.length">
              结果缺失信息：{{ point.generationMissingInformation.join('；') }}
            </p>
            <p v-if="point.missingInformation.length">
              缺失信息：{{ point.missingInformation.join('；') }}
            </p>
            <details
              v-for="testcase in testcasesForPoint(point)"
              :key="`${testcase.title}-${testcase.status}`"
            >
              <summary>{{ testcase.title }} · {{ candidateStatusText(testcase.status) }}</summary>
              <dl class="task-detail__result-details">
                <div><dt>用例名称</dt><dd>{{ testcase.name || testcase.title }}</dd></div>
                <div><dt>优先级</dt><dd>优先级：{{ testcase.priority || '中' }}</dd></div>
                <div><dt>前提约束</dt><dd>{{ testcase.preconditions.join('\n') || '-' }}</dd></div>
                <div v-if="testcase.initialization?.hardwareConfiguration.length">
                  <dt>硬件初始化</dt><dd>{{ testcase.initialization.hardwareConfiguration.join('\n') }}</dd>
                </div>
                <div v-if="testcase.initialization?.softwareConfiguration.length">
                  <dt>软件初始化</dt><dd>{{ testcase.initialization.softwareConfiguration.join('\n') }}</dd>
                </div>
                <div v-if="testcase.initialization?.testConfiguration.length">
                  <dt>测试初始化</dt><dd>{{ testcase.initialization.testConfiguration.join('\n') }}</dd>
                </div>
                <div v-if="testcase.initialization?.parameterConfiguration.length">
                  <dt>参数初始化</dt><dd>{{ testcase.initialization.parameterConfiguration.join('\n') }}</dd>
                </div>
                <div v-if="testcase.inputs?.length">
                  <dt>测试输入</dt><dd>{{ testcase.inputs.map(structuredInputText).join('\n') }}</dd>
                </div>
                <div><dt>执行步骤</dt><dd>{{ testcase.steps.map(step => `${step.stepNo}. ${step.action}`).join('\n') }}</dd></div>
                <div><dt>逐步预期</dt><dd>{{ testcase.steps.map(step => `${step.stepNo}. ${step.expected}`).join('\n') }}</dd></div>
                <div v-if="testcase.steps.some(step => step.evaluationCriteria)">
                  <dt>逐步评价</dt><dd>{{ testcase.steps.map(step => `${step.stepNo}. ${step.evaluationCriteria}`).join('\n') }}</dd>
                </div>
                <div v-if="testcase.steps.some(step => step.terminationOrError)">
                  <dt>异常或终止提示</dt><dd>{{ testcase.steps.filter(step => step.terminationOrError).map(step => `${step.stepNo}. ${step.terminationOrError}`).join('\n') }}</dd>
                </div>
                <div v-if="testcase.steps.some(step => step.resultCollection)">
                  <dt>逐步结果采集</dt><dd>{{ testcase.steps.map(step => `${step.stepNo}. ${step.resultCollection}`).join('\n') }}</dd>
                </div>
                <div v-if="testcase.expectedResults?.length">
                  <dt>总体预期</dt><dd>{{ testcase.expectedResults.join('\n') }}</dd>
                </div>
                <div v-if="testcase.evaluationCriteria">
                  <dt>执行评价标准</dt><dd>{{ testcase.evaluationCriteria }}</dd>
                </div>
                <div v-if="testcase.resultEvaluationCriteria">
                  <dt>结果评价标准</dt><dd>{{ testcase.resultEvaluationCriteria }}</dd>
                </div>
                <div v-if="testcase.terminationConditions?.length">
                  <dt>终止条件</dt><dd>{{ testcase.terminationConditions.join('\n') }}</dd>
                </div>
                <div v-if="testcase.resultCollection">
                  <dt>结果采集</dt><dd>{{ testcase.resultCollection }}</dd>
                </div>
                <div v-if="testcase.authoringInformation?.author || testcase.authoringInformation?.date">
                  <dt>编写信息</dt><dd>{{ [testcase.authoringInformation.author, testcase.authoringInformation.date].filter(Boolean).join(' · ') }}</dd>
                </div>
                <div><dt>对应需求</dt><dd>{{ testcase.requirementSummaries.join('\n') || '-' }}</dd></div>
                <div v-if="testcase.missingInformation.length">
                  <dt>待补充信息</dt><dd>{{ testcase.missingInformation.join('\n') }}</dd>
                </div>
              </dl>
            </details>
            <nav
              v-if="isV2Structured && testcasePageResult && testcasePageResult.totalItems > testcasePageResult.size"
              class="task-detail__pagination"
              aria-label="测试用例分页"
            >
              <button
                type="button"
                :disabled="loading || testcasePageResult.page === 0"
                @click="changeStructuredPage('testcases', testcasePageResult.page - 1)"
              >
                上一页
              </button>
              <span>第 {{ testcasePageResult.page + 1 }} 页，共 {{ testcasePageResult.totalItems }} 条</span>
              <button
                type="button"
                :disabled="loading || !testcasePageResult.hasNext"
                @click="changeStructuredPage('testcases', testcasePageResult.page + 1)"
              >
                下一页
              </button>
            </nav>
          </article>
        </div>
        <nav
          v-if="isV2Structured && testPointPageResult && testPointPageResult.totalItems > testPointPageResult.size"
          class="task-detail__pagination"
          aria-label="测试点分页"
        >
          <button
            type="button"
            :disabled="loading || testPointPageResult.page === 0"
            @click="changeStructuredPage('testPoints', testPointPageResult.page - 1)"
          >
            上一页
          </button>
          <span>第 {{ testPointPageResult.page + 1 }} 页，共 {{ testPointPageResult.totalItems }} 条</span>
          <button
            type="button"
            :disabled="loading || !testPointPageResult.hasNext"
            @click="changeStructuredPage('testPoints', testPointPageResult.page + 1)"
          >
            下一页
          </button>
        </nav>
        <p
          v-if="!displayedTestPoints.length"
          data-state="no-results"
        >
          {{ structuredTestcaseEmptyText() }}
        </p>
      </section>

      <section
        v-if="!structuredResult"
        class="task-detail__section"
        aria-labelledby="issue-title"
      >
        <h2 id="issue-title">
          审查发现
        </h2>
        <div
          v-if="detail.auditRows?.length"
          class="task-detail__cards"
        >
          <article
            v-for="audit in detail.auditRows"
            :key="`${audit.sequence}-${audit.subjectOrFeature}-${audit.issueCategory}`"
          >
            <h3>{{ valueOrDash(audit.subjectOrFeature) }}</h3>
            <p>问题分类：{{ valueOrDash(audit.issueCategory) }}</p>
            <details>
              <summary>查看证据对照</summary>
              <p class="task-detail__multiline">
                {{ valueOrDash(audit.evidenceComparison) }}
              </p>
            </details>
          </article>
        </div>
        <p v-else>
          -
        </p>
      </section>

      <section
        v-if="!structuredResult"
        class="task-detail__section"
        aria-labelledby="case-title"
      >
        <h2 id="case-title">
          测试用例
        </h2>
        <div
          v-if="detail.testCaseRows?.length"
          class="task-detail__cards"
        >
          <article
            v-for="testCase in detail.testCaseRows"
            :key="`${testCase.caseName}-${testCase.featureModule}`"
          >
            <h3>{{ valueOrDash(testCase.caseName) }}</h3>
            <p>功能模块：{{ valueOrDash(testCase.featureModule) }}</p>
            <details>
              <summary>查看完整内容</summary>
              <dl class="task-detail__result-details">
                <div>
                  <dt>前提约束</dt>
                  <dd class="task-detail__multiline">
                    {{ valueOrDash(testCase.preconditions) }}
                  </dd>
                </div>
                <div>
                  <dt>执行步骤</dt>
                  <dd class="task-detail__multiline">
                    {{ valueOrDash(testCase.executionSteps) }}
                  </dd>
                </div>
                <div>
                  <dt>预期结果</dt>
                  <dd class="task-detail__multiline">
                    {{ valueOrDash(testCase.expectedResult) }}
                  </dd>
                </div>
                <div>
                  <dt>对应需求内容</dt>
                  <dd class="task-detail__multiline">
                    {{ valueOrDash(testCase.requirementContent) }}
                  </dd>
                </div>
              </dl>
            </details>
          </article>
        </div>
        <p v-else>
          -
        </p>
      </section>

      <p
        v-if="detail.failureSummary"
        class="task-detail__error"
        role="alert"
      >
        失败摘要：{{ detail.failureSummary }}
      </p>
      <p
        v-else-if="batchFailureSummary"
        class="task-detail__error"
        role="alert"
      >
        未完成原因：{{ batchFailureSummary }}
      </p>
      <a
        v-if="downloadUrl"
        class="task-detail__download-action"
        :href="downloadUrl"
      >
        {{ structuredResult && structuredResult.coverageStatus !== 'COMPLETE' ? '下载 Excel（覆盖结果见页面）' : detail.status === 'PARTIAL' ? '下载已完成部分的 Excel' : '下载 Excel' }}
      </a>
    </div>
    <div
      v-else
      class="task-detail__empty"
      data-state="empty"
      role="status"
    >
      <p>暂未获得可展示的任务详情。</p>
      <button
        type="button"
        @click="loadTask()"
      >
        重新加载
      </button>
    </div>
    <dialog
      v-if="pendingAction"
      ref="confirmationDialog"
      class="task-detail__dialog"
      aria-labelledby="task-action-confirm-title"
      @cancel.prevent
    >
      <h2 id="task-action-confirm-title">
        {{ pendingAction === 'cancel' ? '确认取消任务' : isStructuredRetry ? '确认重试失败的结构化处理' : isDiscoveryRetry ? '确认重新识别材料并生成测试用例' : '确认重试失败批次' }}
      </h2>
      <p>
        {{ pendingAction === 'cancel' ? '任务会在安全检查点停止，已接受的结果将保留。' : isStructuredRetry ? '仅重新执行一个符合条件的失败处理项，已保存结果不会被覆盖。' : isDiscoveryRetry ? '系统将重新识别材料中的功能并生成测试用例。' : '仅会重新执行可重试的失败批次，已接受的结果不会被覆盖。' }}
      </p>
      <p
        v-if="actionError"
        class="task-detail__error"
        role="alert"
      >
        {{ actionError }}
      </p>
      <div class="task-detail__dialog-actions">
        <button
          type="button"
          :disabled="mutating"
          @click="closeConfirmation"
        >
          返回详情
        </button>
        <button
          type="button"
          :disabled="mutating"
          @click="confirmAction"
        >
          {{ mutating ? '提交中…' : pendingAction === 'cancel' ? '确认取消' : isDiscoveryRetry ? '确认重新生成' : '确认重试' }}
        </button>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
/* [Req-ID]: REQ-UIX-009 — dark glass stage flow on the mission-control surfaces. */
.task-detail__progress-heading {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-16);
  align-items: start;
  justify-content: space-between;
}

.task-detail__progress-heading > .status-chip {
  margin: var(--space-0);
}

.task-detail__stage-flow {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--space-8);
  margin: var(--space-24) var(--space-0);
  padding: var(--space-0);
  list-style: none;
}

.task-detail__stage-flow--structured {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

/* Pipeline stepper: node dots linked by connector hairlines read as one process line. */
.task-detail__stage {
  position: relative;
  min-width: 0;
  padding: var(--space-32) var(--space-8) var(--space-8);
  color: var(--color-ink-tertiary);
  text-align: center;
}

/* Connector runs from this node's center to the next node's center. */
.task-detail__stage::before {
  position: absolute;
  top: 7px;
  right: calc(-50% + 14px);
  left: calc(50% + 14px);
  height: 2px;
  background: var(--color-glass-border);
  content: '';
}

.task-detail__stage:last-child::before {
  display: none;
}

.task-detail__stage::after {
  position: absolute;
  top: 1px;
  left: 50%;
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-glass-border-strong);
  border-radius: 50%;
  background: var(--color-shell-deep);
  content: '';
  transform: translateX(-50%);
  transition: border-color var(--motion-fast), background var(--motion-fast), box-shadow var(--motion-fast);
}

.task-detail__stage strong,
.task-detail__stage span {
  display: block;
}

.task-detail__stage span {
  margin-top: var(--space-4);
  font: var(--type-caption);
}

.task-detail__stage--complete {
  color: var(--color-ink-secondary);
}

.task-detail__stage--complete::before {
  background: var(--color-success);
}

.task-detail__stage--complete::after {
  border-color: var(--color-success);
  background: var(--color-success);
  box-shadow: 0 0 12px var(--color-success-glass), 0 0 4px var(--color-success);
}

.task-detail__stage--complete strong {
  color: var(--color-success-ink);
}

.task-detail__stage--failed {
  color: var(--color-error-ink);
}

.task-detail__stage--failed::before {
  background: var(--color-error);
}

.task-detail__stage--failed::after {
  border-color: var(--color-error);
  background: var(--color-error);
  box-shadow: 0 0 12px var(--color-error-glass), 0 0 4px var(--color-error);
}

.task-detail__stage--failed strong {
  color: var(--color-error-ink);
}

.task-detail__stage--cancelled {
  color: var(--color-ink-secondary);
}

.task-detail__stage--cancelled::after {
  border-color: var(--color-ink-tertiary);
  background: var(--color-shell-deep);
}

.task-detail__stage--cancelled strong {
  color: var(--color-ink-primary);
}

.task-detail__stage--current {
  color: var(--color-ink-secondary);
}

.task-detail__stage--current::before {
  background: var(--gradient-edge-cyan);
}

.task-detail__stage--current::after {
  border-color: var(--color-accent-cyan);
  background: var(--color-accent-cyan);
  box-shadow: 0 0 16px rgb(34 211 238 / 65%);
  animation: status-pulse 1.6s ease-in-out infinite;
}

.task-detail__stage--current strong {
  color: var(--color-ink-primary);
}

.task-detail__progress-counts {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.task-detail__business-reason {
  margin-top: var(--space-16);
  color: var(--color-ink-secondary);
}

.task-detail__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-12);
  margin-top: var(--space-16);
  color: var(--color-ink-secondary);
}

.task-detail__empty {
  display: grid;
  justify-items: start;
  gap: var(--space-16);
  margin-inline: var(--space-32);
  padding: var(--space-24);
  border: 1px dashed var(--color-glass-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-glass-muted);
  color: var(--color-ink-secondary);
}

@media (max-width: 760px) {
  .task-detail__stage-flow,
  .task-detail__progress-counts {
    grid-template-columns: 1fr;
  }

  /* Single-column layouts turn the pipeline vertical: rail on the left. */
  .task-detail__stage {
    padding: var(--space-0) var(--space-0) var(--space-0) var(--space-32);
    text-align: left;
  }

  .task-detail__stage::before {
    top: 22px;
    right: auto;
    bottom: calc(-1 * var(--space-8));
    left: 7px;
    width: 2px;
    height: auto;
  }

  .task-detail__stage::after {
    top: 3px;
    left: 0;
    transform: none;
  }

  .task-detail__empty {
    margin-inline: var(--space-16);
  }
}

@media (prefers-reduced-motion: reduce) {
  .task-detail__stage--current::after {
    animation: none;
  }
}
</style>
