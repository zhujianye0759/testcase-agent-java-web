<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'

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

async function loadTask() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await props.getTask(props.taskId)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '任务详情加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
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
</script>

<template>
  <!-- [Req-ID]: REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008 -->
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
