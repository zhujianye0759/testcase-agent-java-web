<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { taskApi, type GenerationTaskListItem, type GenerationTaskPage, type TaskListQuery } from '../api/taskApi'

const props = withDefaults(defineProps<{
  loadTasks?: (pagination: TaskListQuery) => Promise<GenerationTaskPage>
}>(), {
  loadTasks: (query: TaskListQuery) => taskApi.listTasks(query),
})

const pageSize = 20
const page = ref(0)
const taskPage = ref<GenerationTaskPage>({ items: [], page: 0, size: pageSize, totalItems: 0 })
const state = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
const errorMessage = ref('')
let requestVersion = 0

const totalPages = computed(() => Math.max(1, Math.ceil(taskPage.value.totalItems / taskPage.value.size)))
const canGoPrevious = computed(() => taskPage.value.page > 0)
const canGoNext = computed(() => taskPage.value.page + 1 < totalPages.value)

onMounted(() => loadPage(0))

async function loadPage(nextPage: number) {
  const version = ++requestVersion
  state.value = 'loading'
  errorMessage.value = ''
  const request = { page: nextPage, size: pageSize }
  try {
    const response = await props.loadTasks(request)
    if (version !== requestVersion) return

    taskPage.value = response
    page.value = response.page
    state.value = response.items.length === 0 ? 'empty' : 'ready'
  } catch (error) {
    if (version !== requestVersion) return

    errorMessage.value = error instanceof Error ? error.message : '任务列表加载失败，请稍后重试。'
    state.value = 'error'
  }
}

function taskName(item: GenerationTaskListItem) {
  return item.taskMode === 'ALL' ? '全部测试用例生成' : '指定功能测试用例生成'
}

function statusText(status: string) {
  return {
    QUEUED: '排队中',
    AUDITING: '审查中',
    GENERATING: '生成中',
    VALIDATING: '校验中',
    RUNNING: '生成中',
    COMPLETED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '生成失败',
    CANCELLED: '已取消',
  }[status] ?? (status || '-')
}

function formatCreatedAt(createdAt?: string) {
  if (!createdAt) return '-'
  const value = new Date(createdAt)
  return Number.isNaN(value.getTime()) ? '-' : value.toLocaleString('zh-CN', { hour12: false })
}

function progressText(item: GenerationTaskListItem) {
  if (item.completedBatches === undefined || item.totalBatches === undefined) return '-'
  return `${item.completedBatches} / ${item.totalBatches} 批次`
}
</script>

<template>
  <!-- [Req-ID]: REQ-WEB-002, REQ-WEB-006, REQ-WEB-007 -->
  <!-- [Req-ID]: REQ-UIX-001, REQ-UIX-004, REQ-UIX-006, REQ-UIX-007 -->
  <section
    class="task-list"
    aria-labelledby="task-list-title"
  >
    <header class="task-list__heading">
      <div>
        <p class="page-eyebrow">
          TASK OPERATIONS
        </p>
        <h1 id="task-list-title">
          共享任务
        </h1>
        <p>查看所有测试用例生成任务的状态、进度和失败摘要。</p>
      </div>
      <p class="task-list__heading-note">
        <span aria-hidden="true">↗</span>所有成员均可查看与处理已授权任务
      </p>
    </header>

    <div
      v-if="state === 'loading'"
      class="task-list__state"
      role="status"
      aria-live="polite"
    >
      正在加载任务…
    </div>
    <div
      v-else-if="state === 'error'"
      class="task-list__state task-list__state--error"
      role="alert"
    >
      <p>{{ errorMessage }}</p>
      <button
        type="button"
        @click="loadPage(page)"
      >
        重试加载
      </button>
    </div>
    <div
      v-else-if="state === 'empty'"
      class="task-list__state"
      data-state="empty"
    >
      <h2>尚无任务</h2>
      <p>创建任务后，可在这里查看生成进度和结果。</p>
      <RouterLink :to="{ name: 'generation-workspace' }">
        创建任务
      </RouterLink>
    </div>
    <div
      v-else-if="state === 'ready'"
      data-state="ready"
    >
      <div class="task-list__overview">
        共 {{ taskPage.totalItems }} 个任务，第 {{ taskPage.page + 1 }} / {{ totalPages }} 页
      </div>
      <div class="task-list__table-wrapper">
        <table>
          <thead>
            <tr>
              <th scope="col">
                任务
              </th>
              <th scope="col">
                创建时间
              </th>
              <th scope="col">
                状态
              </th>
              <th scope="col">
                进度
              </th>
              <th scope="col">
                失败摘要
              </th>
              <th scope="col">
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in taskPage.items"
              :key="item.id"
            >
              <td>
                <strong>{{ taskName(item) }}</strong>
              </td>
              <td>{{ formatCreatedAt(item.createdAt) }}</td>
              <td>
                <span
                  class="task-list__status status-chip"
                  :class="`task-list__status--${item.status.toLowerCase()}`"
                  :aria-label="`状态：${statusText(item.status)}`"
                >{{ statusText(item.status) }}</span>
              </td>
              <td>{{ progressText(item) }}</td>
              <td>{{ item.failureSummary || '-' }}</td>
              <td>
                <RouterLink :to="{ name: 'task-detail', params: { taskId: item.id } }">
                  查看
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav
        class="task-list__pagination"
        aria-label="任务列表分页"
      >
        <button
          type="button"
          :disabled="!canGoPrevious"
          @click="loadPage(taskPage.page - 1)"
        >
          上一页
        </button>
        <span>第 {{ taskPage.page + 1 }} 页，共 {{ totalPages }} 页</span>
        <button
          type="button"
          :disabled="!canGoNext"
          @click="loadPage(taskPage.page + 1)"
        >
          下一页
        </button>
      </nav>
    </div>
  </section>
</template>
