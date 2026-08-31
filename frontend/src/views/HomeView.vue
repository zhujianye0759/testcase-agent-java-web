<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  taskApi,
  type CreateTaskPayload,
  type ScopeCatalog,
  type TaskCreated,
} from '../api/taskApi'

const props = withDefaults(defineProps<{
  createTask?: (payload: CreateTaskPayload) => Promise<TaskCreated>
  loadTaskOptions?: (refresh?: boolean) => Promise<ScopeCatalog>
}>(), {
  createTask: (payload: CreateTaskPayload) => taskApi.createTask(payload),
  loadTaskOptions: (refresh = false) => taskApi.getTaskOptions(refresh),
})

const router = useRouter()
const submitting = ref(false)
const submitError = ref('')
const optionError = ref('')
const loadingOptions = ref(true)
const scopeCatalog = ref<ScopeCatalog>({ knowledgeBases: [] })
const submitErrorElement = ref<{ focus: () => void }>()
let loadSequence = 0
const form = reactive({
  taskMode: 'ALL' as CreateTaskPayload['taskMode'],
  featureDescription: '',
  knowledgeBaseId: '',
  systemId: '',
  versionId: '',
  scopeSelectionIds: [] as string[],
  fewShotPolicy: 'AUTO' as CreateTaskPayload['fewShotPolicy'],
  prompt: '',
  approvedScopeVersion: '',
  approvedFunctions: [{ functionKey: '', name: '', path: '', description: '' }],
})

const knowledgeBases = computed(() => scopeCatalog.value.knowledgeBases)
const selectedKnowledgeBase = computed(() => knowledgeBases.value.find(item => item.id === form.knowledgeBaseId))
const systems = computed(() => selectedKnowledgeBase.value?.systems ?? [])
const selectedSystem = computed(() => systems.value.find(item => item.id === form.systemId))
const versions = computed(() => selectedSystem.value?.versions ?? [])
const selectedVersion = computed(() => versions.value.find(item => item.id === form.versionId))
const materialTypes = computed(() => selectedVersion.value?.materialTypes ?? [])
const materialChoices = computed(() => materialTypes.value.flatMap(material => {
  if (material.documents !== undefined) {
    return material.documents.map(document => ({
      id: document.id,
      materialLabel: material.label,
      documentLabel: document.label,
      fromDocumentOptions: true,
    }))
  }
  return [{
    id: material.id,
    materialLabel: material.label,
    documentLabel: `${material.documentCount} 份可用文档`,
    fromDocumentOptions: false,
  }]
}))
const hasCatalog = computed(() => knowledgeBases.value.length > 0)
const approvedScopeComplete = computed(() => form.approvedScopeVersion.trim().length > 0
  && form.approvedFunctions.length > 0
  && form.approvedFunctions.every(item => item.functionKey.trim() && item.name.trim() && item.path.trim())
  && (form.taskMode !== 'FEATURE' || form.approvedFunctions.length === 1))
const canSubmit = computed(() => !submitting.value && !loadingOptions.value
  && form.scopeSelectionIds.length > 0 && approvedScopeComplete.value)

async function loadOptions(refresh = false) {
  const requestSequence = ++loadSequence
  loadingOptions.value = true
  optionError.value = ''
  try {
    const loaded = await props.loadTaskOptions(refresh)
    if (requestSequence !== loadSequence) return
    scopeCatalog.value = loaded
    initializeSelection()
    if (knowledgeBases.value.length === 0) {
      optionError.value = '暂时没有可用于生成测试用例的材料范围。请联系知识库管理员完成材料配置后重新加载。'
    }
  } catch {
    if (requestSequence !== loadSequence) return
    optionError.value = '暂时无法加载可用材料范围。请检查网络后重新加载。'
  } finally {
    if (requestSequence === loadSequence) loadingOptions.value = false
  }
}

onMounted(loadOptions)

function initializeSelection() {
  form.knowledgeBaseId = knowledgeBases.value.length === 1 ? knowledgeBases.value[0].id : ''
  resetBusinessSystemScope()
}

/**
 * Keeps the legacy system coordinate internal: one eligible requirement knowledge base is one business system.
 * A missing or ambiguous internal coordinate deliberately leaves downstream choices unavailable.
 */
function resetBusinessSystemScope() {
  form.systemId = systems.value.length === 1 ? systems.value[0].id : ''
  resetVersionSelection()
}

function resetVersionSelection() {
  form.versionId = versions.value.length === 1 ? versions.value[0].id : ''
  resetMaterialSelection()
}

function resetMaterialSelection() {
  form.scopeSelectionIds = materialChoices.value.length === 1 ? [materialChoices.value[0].id] : []
}

function addApprovedFunction() {
  form.approvedFunctions.push({ functionKey: '', name: '', path: '', description: '' })
}

function removeApprovedFunction(index: number) {
  if (form.approvedFunctions.length > 1) form.approvedFunctions.splice(index, 1)
}

async function submitTask() {
  if (!canSubmit.value) return
  if (form.taskMode === 'FEATURE' && !form.featureDescription.trim()) {
    submitError.value = '请填写要生成的功能名称或功能描述。'
    await nextTick()
    submitErrorElement.value?.focus()
    return
  }
  if (!approvedScopeComplete.value) {
    submitError.value = form.taskMode === 'FEATURE' && form.approvedFunctions.length !== 1
      ? '指定功能生成时，已审核功能范围必须恰好包含一个功能。'
      : '请完整填写已审核功能范围版本、功能标识、名称和路径。'
    await nextTick()
    submitErrorElement.value?.focus()
    return
  }

  submitting.value = true
  submitError.value = ''
  try {
    const task = await props.createTask({
      taskMode: form.taskMode,
      featureDescription: form.featureDescription.trim(),
      fewShotPolicy: form.fewShotPolicy,
      schemaVersion: '2.0',
      promptVersion: '2.0',
      scopeSelectionIds: [...form.scopeSelectionIds],
      prompt: form.prompt.trim(),
      workflowVersion: '2.0',
      inputVersion: '2.0',
      artifactVersion: '2.0',
      approvedFunctionScope: {
        scopeVersion: form.approvedScopeVersion.trim(),
        functions: form.approvedFunctions.map(item => ({
          functionKey: item.functionKey.trim(),
          name: item.name.trim(),
          path: item.path.trim(),
          description: item.description.trim(),
        })),
      },
    })
    await router.push({ name: 'task-detail', params: { taskId: task.id } })
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : '创建任务失败，请稍后重试。'
    await nextTick()
    submitErrorElement.value?.focus()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <!-- [Req-ID]: REQ-WEB-001, REQ-WEB-006, REQ-WEB-007, REQ-WEB-009, REQ-WEB-010, REQ-FSC-006 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-003, REQ-UIX-006, REQ-UIX-007, REQ-UIX-009 -->
  <section
    class="generation-workspace"
    aria-labelledby="generation-page-title"
  >
    <header class="generation-workspace__hero">
      <div>
        <p class="page-eyebrow">
          TEST DESIGN WORKSPACE
        </p>
        <h1 id="generation-page-title">
          测试用例生成
        </h1>
        <p class="generation-workspace__lead">
          以材料为依据，自动沉淀审查发现与可执行的测试用例。
        </p>
      </div>
      <!-- Decorative radar: aria-hidden, carries no status meaning. -->
      <div
        class="generation-workspace__hero-visual"
        aria-hidden="true"
      >
        <span />
      </div>
      <div class="generation-workspace__hero-note">
        <span aria-hidden="true">01</span>
        <p>任务将在后台执行<br>结果可在共享任务中查看</p>
      </div>
    </header>
    <div class="generation-workspace__context">
      <p>选择已授权材料范围后即可开始。系统会保留本次任务的材料快照，方便后续查看和下载。</p>
      <RouterLink :to="{ name: 'task-list' }">
        查看共享任务
      </RouterLink>
    </div>

    <form
      class="task-form"
      @submit.prevent="submitTask"
    >
      <fieldset
        class="task-form__mode"
        :disabled="submitting || loadingOptions || !hasCatalog"
      >
        <legend>选择生成方式</legend>
        <label
          data-testid="all-mode-card"
          class="task-form__choice"
          :class="{ 'task-form__choice--selected': form.taskMode === 'ALL' }"
          :data-selected="form.taskMode === 'ALL'"
        >
          <input
            v-model="form.taskMode"
            type="radio"
            name="taskMode"
            value="ALL"
          >
          <span>
            <strong>生成全部测试用例</strong>
            <small>推荐 · 按已审核功能范围分批生成</small>
          </span>
        </label>
        <label
          data-testid="feature-mode-card"
          class="task-form__choice"
          :class="{ 'task-form__choice--selected': form.taskMode === 'FEATURE' }"
          :data-selected="form.taskMode === 'FEATURE'"
        >
          <input
            v-model="form.taskMode"
            type="radio"
            name="taskMode"
            value="FEATURE"
          >
          <span>
            <strong>指定功能生成</strong>
            <small>输入功能名称或描述，聚焦当前需要验证的内容</small>
          </span>
        </label>
        <p class="task-form__help">
          系统将以已审核功能范围为目标，并从已授权材料中提取需求依据。
        </p>
        <label v-if="form.taskMode === 'FEATURE'">
          功能名称或功能描述
          <input
            v-model="form.featureDescription"
            name="featureDescription"
            placeholder="例如：用户登录与忘记密码"
            required
          >
        </label>
      </fieldset>

      <fieldset :disabled="submitting || loadingOptions || !hasCatalog">
        <legend>本次材料范围</legend>
        <p
          v-if="loadingOptions"
          class="task-form__loading"
          data-state="loading"
          role="status"
        >
          正在准备可用材料范围…
        </p>
        <div
          v-else-if="hasCatalog"
          class="task-form__scope-cascade"
        >
          <label v-if="knowledgeBases.length > 1">
            业务系统
            <select
              v-model="form.knowledgeBaseId"
              name="businessSystemId"
              required
              @change="resetBusinessSystemScope"
            >
              <option
                value=""
                disabled
              >请选择业务系统</option>
              <option
                v-for="option in knowledgeBases"
                :key="option.id"
                :value="option.id"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
          <div
            v-else
            class="task-form__scope-value"
          >
            <span>业务系统</span><strong>{{ selectedKnowledgeBase?.label }}</strong>
          </div>

          <label v-if="versions.length > 1">
            版本
            <select
              v-model="form.versionId"
              name="versionId"
              required
              @change="resetMaterialSelection"
            >
              <option
                value=""
                disabled
              >请选择版本</option>
              <option
                v-for="option in versions"
                :key="option.id"
                :value="option.id"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
          <div
            v-else-if="selectedVersion"
            class="task-form__scope-value"
          >
            <span>版本</span><strong>{{ selectedVersion.label }}</strong>
          </div>

          <div
            v-if="selectedVersion"
            data-testid="scope-summary"
            class="task-form__scope-summary"
          >
            <strong>{{ selectedKnowledgeBase?.label }} / {{ selectedVersion.label }}</strong>
            <span>
              {{ materialTypes.length === 1 ? materialTypes[0].label : '仅使用下方选中材料中的已解析、已启用文档' }}
            </span>
          </div>

          <div
            v-if="materialTypes.length"
            class="task-form__materials"
          >
            <p>材料文档（可多选）</p>
            <p
              v-if="materialChoices.length === 0"
              class="task-form__selection-hint"
              role="status"
            >
              当前材料范围没有可选择的已完成文档，请刷新后再试。
            </p>
            <p
              v-if="materialChoices.length === 1"
              class="task-form__material-single"
            >
              <strong>{{ materialChoices[0].materialLabel }}</strong>
              <span>{{ materialChoices[0].documentLabel }} · 已自动选择</span>
            </p>
            <label
              v-for="material in materialChoices.length > 1 ? materialChoices : []"
              :key="material.id"
              class="task-form__material-choice"
            >
              <input
                v-model="form.scopeSelectionIds"
                type="checkbox"
                :name="material.fromDocumentOptions ? 'materialDocumentIds' : 'materialTypeIds'"
                :value="material.id"
              >
              <span><strong>{{ material.materialLabel }}</strong><small>{{ material.documentLabel }}</small></span>
            </label>
            <p
              v-if="materialChoices.length > 1 && form.scopeSelectionIds.length === 0"
              class="task-form__selection-hint"
            >
              请至少选择一份材料文档。
            </p>
          </div>
        </div>
        <p
          v-else-if="!optionError"
          class="task-form__loading"
          role="status"
        >
          当前没有可选材料范围。
        </p>
      </fieldset>

      <fieldset :disabled="submitting">
        <legend>已审核功能范围</legend>
        <p class="task-form__help">
          使用测试准入审核已发布的版本化功能范围。这里不会重新比对或扩张功能清单。
        </p>
        <label>
          审核范围版本
          <input
            v-model="form.approvedScopeVersion"
            name="approvedScopeVersion"
            placeholder="例如：scope-v2-20260831"
            required
          >
        </label>
        <div
          v-for="(approvedFunction, index) in form.approvedFunctions"
          :key="index"
          class="task-form__approved-function"
          data-testid="approved-function"
        >
          <label>
            功能标识（由准入审核结果提供）
            <input
              v-model="approvedFunction.functionKey"
              :name="`approvedFunctionKey-${index}`"
              required
            >
          </label>
          <label>
            功能名称
            <input
              v-model="approvedFunction.name"
              :name="`approvedFunctionName-${index}`"
              required
            >
          </label>
          <label>
            功能路径
            <input
              v-model="approvedFunction.path"
              :name="`approvedFunctionPath-${index}`"
              required
            >
          </label>
          <label>
            功能说明（可选）
            <textarea
              v-model="approvedFunction.description"
              :name="`approvedFunctionDescription-${index}`"
            />
          </label>
          <button
            v-if="form.approvedFunctions.length > 1"
            type="button"
            class="task-form__secondary-action"
            :aria-label="`移除第 ${index + 1} 个已审核功能`"
            @click="removeApprovedFunction(index)"
          >
            移除此功能
          </button>
        </div>
        <button
          v-if="form.taskMode === 'ALL'"
          type="button"
          class="task-form__secondary-action"
          @click="addApprovedFunction"
        >
          添加已审核功能
        </button>
      </fieldset>

      <!-- Execution rail: strategy, notes, and launch actions grouped for the two-column console layout. -->
      <div class="task-form__rail">
        <fieldset :disabled="submitting || loadingOptions || !hasCatalog">
          <legend>生成策略</legend>
          <p class="task-form__strategy-note">
            <strong>自动参考优质示例（推荐）</strong><span>用于优化用例结构和表达，不替代正式材料依据</span>
          </p>
          <details>
            <summary>高级设置</summary>
            <label>
              <input
                v-model="form.fewShotPolicy"
                type="checkbox"
                true-value="NONE"
                false-value="AUTO"
              >
              不参考示例（用于对照）
            </label>
          </details>
        </fieldset>

        <label class="task-form__wide">
          补充说明（可选）
          <textarea
            v-model="form.prompt"
            name="prompt"
            placeholder="例如：请重点覆盖异常输入、权限边界和关键业务规则。"
          />
        </label>

        <p
          v-if="optionError"
          class="task-form__error"
          role="alert"
        >
          {{ optionError }}
        </p>
        <p
          v-if="submitError"
          ref="submitErrorElement"
          class="task-form__error"
          role="alert"
          tabindex="-1"
        >
          {{ submitError }}
        </p>
        <div class="task-form__actions">
          <p>开始后将转入后台执行，您可随时在共享任务中查看进度。</p>
          <button
            class="task-form__secondary-action"
            type="button"
            :disabled="loadingOptions || submitting"
            @click="loadOptions(true)"
          >
            重新加载材料范围
          </button>
          <button
            type="submit"
            :disabled="!canSubmit"
          >
            {{ submitting ? '提交中…' : '开始生成测试用例' }}
          </button>
        </div>
      </div>
    </form>
  </section>
</template>
