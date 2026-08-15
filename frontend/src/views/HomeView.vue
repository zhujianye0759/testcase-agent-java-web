<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { taskApi, type CreateTaskPayload, type TaskCreated, type TaskScopeOption } from '../api/taskApi'

const props = withDefaults(defineProps<{
  createTask?: (payload: CreateTaskPayload) => Promise<TaskCreated>
  loadTaskOptions?: () => Promise<TaskScopeOption[]>
}>(), {
  createTask: (payload: CreateTaskPayload) => taskApi.createTask(payload),
  loadTaskOptions: () => taskApi.getTaskOptions(),
})

const router = useRouter()
const submitting = ref(false)
const submitError = ref('')
const optionError = ref('')
const loadingOptions = ref(true)
const scopeOptions = ref<TaskScopeOption[]>([])
const submitErrorElement = ref<{ focus: () => void }>()
const form = reactive({
  taskMode: 'ALL' as CreateTaskPayload['taskMode'],
  featureDescription: '',
  scopeOptionId: '',
  fewShotPolicy: 'AUTO' as CreateTaskPayload['fewShotPolicy'],
  prompt: '',
})

async function loadOptions() {
  loadingOptions.value = true
  optionError.value = ''
  try {
    scopeOptions.value = await props.loadTaskOptions()
    if (scopeOptions.value.length === 1) {
      form.scopeOptionId = scopeOptions.value[0].id
    }
    if (scopeOptions.value.length === 0) {
      optionError.value = '暂时没有可用于生成测试用例的材料范围。请联系知识库管理员完成材料配置后重新加载。'
    }
  } catch {
    optionError.value = '暂时无法加载可用材料范围。请检查网络后重新加载。'
  } finally {
    loadingOptions.value = false
  }
}

onMounted(loadOptions)

async function submitTask() {
  if (submitting.value || !form.scopeOptionId) return
  if (form.taskMode === 'FEATURE' && !form.featureDescription.trim()) {
    submitError.value = '请填写要生成的功能名称或功能描述。'
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
      schemaVersion: '1.0',
      promptVersion: '1.0',
      scopeOptionId: form.scopeOptionId,
      prompt: form.prompt.trim(),
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
  <!-- [Req-ID]: REQ-WEB-001, REQ-WEB-006, REQ-WEB-007 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-003, REQ-UIX-006, REQ-UIX-007 -->
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
        :disabled="submitting || loadingOptions || scopeOptions.length === 0"
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
            <small>推荐 · 自动识别材料中的功能并分批生成</small>
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
          系统将从已授权材料中识别功能并分批生成。
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

      <fieldset :disabled="submitting || loadingOptions || scopeOptions.length === 0">
        <legend>本次材料范围</legend>
        <p
          v-if="loadingOptions"
          class="task-form__loading"
          data-state="loading"
          role="status"
        >
          正在准备可用材料范围…
        </p>
        <p
          v-else-if="scopeOptions.length === 1"
          data-testid="scope-summary"
          class="task-form__scope-summary"
        >
          {{ scopeOptions[0].label }}
        </p>
        <label v-else>
          已授权材料范围
          <select
            v-model="form.scopeOptionId"
            name="scopeOptionId"
            required
          >
            <option
              value=""
              disabled
            >请选择材料范围</option>
            <option
              v-for="option in scopeOptions"
              :key="option.id"
              :value="option.id"
            >{{ option.label }}</option>
          </select>
        </label>
      </fieldset>

      <fieldset :disabled="submitting || loadingOptions || scopeOptions.length === 0">
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
          @click="loadOptions"
        >
          重新加载材料范围
        </button>
        <button
          type="submit"
          :disabled="submitting || loadingOptions || scopeOptions.length === 0"
        >
          {{ submitting ? '提交中…' : '开始生成测试用例' }}
        </button>
      </div>
    </form>
  </section>
</template>
