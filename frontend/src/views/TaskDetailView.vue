<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { taskApi, type GenerationTaskDetail } from '../api/taskApi'

const props = withDefaults(defineProps<{
  taskId: string
  getTask?: (taskId: string) => Promise<GenerationTaskDetail>
  cancelTask?: (taskId: string) => Promise<void>
  retryTask?: (taskId: string) => Promise<void>
}>(), {
  getTask: (taskId: string) => taskApi.getTask(taskId),
  cancelTask: (taskId: string) => taskApi.cancelTask(taskId),
  retryTask: (taskId: string) => taskApi.retryTask(taskId),
})

const detail = ref<GenerationTaskDetail>()
const loading = ref(true)
const loadError = ref('')
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
const canCancel = computed(() => detail.value !== undefined && !['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(detail.value.status))
const hasFailedBatch = computed(() => detail.value?.batches?.some((batch) => batch.status === 'FAILED') ?? false)
const isDiscoveryRetry = computed(() => detail.value?.status === 'FAILED' && !hasFailedBatch.value)
const canRetry = computed(() => detail.value !== undefined
  && (detail.value.status === 'FAILED' || (detail.value.status === 'PARTIAL' && hasFailedBatch.value)))
const batchFailureSummary = computed(() => detail.value?.batches
  ?.filter((batch) => batch.status === 'FAILED' && batch.failureSummary)
  .map((batch) => batch.failureSummary)
  .join('；') ?? '')
const businessProgress = computed(() => detail.value?.businessProgress)
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

async function loadTask() {
  const requestTaskId = props.taskId
  const requestSequence = ++loadSequence
  loading.value = true
  loadError.value = ''
  try {
    const loadedDetail = await props.getTask(requestTaskId)
    if (!isCurrentLoad(requestSequence, requestTaskId)) return
    detail.value = loadedDetail
  } catch (error) {
    if (!isCurrentLoad(requestSequence, requestTaskId)) return
    loadError.value = error instanceof Error ? error.message : '任务详情加载失败，请稍后重试。'
  } finally {
    if (isCurrentLoad(requestSequence, requestTaskId)) loading.value = false
  }
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
    function_list: '功能清单',
    interface_list: '接口清单',
    detailed_design: '详细设计',
    environment_config: '环境配置',
  }
  return types.split(/[,，]/).map((type) => labels[type.trim()] ?? type.trim()).join('、')
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
    actionError.value = error instanceof Error ? error.message : '操作未完成，请稍后重试。'
  } finally {
    mutating.value = false
  }
}

onMounted(loadTask)
watch(() => props.taskId, () => { void loadTask() })
</script>

<template>
  <!-- [Req-ID]: REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008, REQ-CWR-001, REQ-CWR-002 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-005, REQ-UIX-006, REQ-UIX-007, REQ-UIX-009 -->
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
      role="alert"
    >
      <p>{{ loadError }}</p>
      <button
        type="button"
        @click="loadTask"
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
          <dd>{{ detail.completedBatches }} / {{ detail.totalBatches }} 个批次已完成</dd>
        </div>
        <div>
          <dt>下载结果</dt>
          <dd>{{ detail.artifactReady ? (detail.status === 'PARTIAL' ? '可下载已完成部分' : '已生成，可下载') : '结果生成后可下载' }}</dd>
        </div>
      </dl>

      <section
        v-if="businessProgress"
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
          {{ isDiscoveryRetry ? '重新识别并生成' : '重试失败批次' }}
        </button>
      </div>

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
        {{ detail.status === 'PARTIAL' ? '下载已完成部分的 Excel' : '下载 Excel' }}
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
        @click="loadTask"
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
        {{ pendingAction === 'cancel' ? '确认取消任务' : isDiscoveryRetry ? '确认重新识别材料并生成测试用例' : '确认重试失败批次' }}
      </h2>
      <p>
        {{ pendingAction === 'cancel' ? '任务会在安全检查点停止，已接受的结果将保留。' : isDiscoveryRetry ? '系统将重新识别材料中的功能并生成测试用例。' : '仅会重新执行可重试的失败批次，已接受的结果不会被覆盖。' }}
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
