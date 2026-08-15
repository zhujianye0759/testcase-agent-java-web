import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import TaskListView from './TaskListView.vue'
import { createAppRouter } from '../router'
import type { GenerationTaskPage, TaskListQuery } from '../api/taskApi'

const firstPage: GenerationTaskPage = {
  items: [{
    id: 'task-old',
    taskMode: 'FEATURE',
    status: 'RUNNING',
    createdAt: '2026-08-14T08:00:00Z',
    totalBatches: 3,
    completedBatches: 1,
    failureSummary: undefined,
    artifactReady: false,
  }],
  page: 0,
  size: 20,
  totalItems: 1,
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}

function mountList(loadTasks: (pagination: TaskListQuery) => Promise<GenerationTaskPage>) {
  const router = createAppRouter(createMemoryHistory())
  return mount(TaskListView, {
    props: { loadTasks },
    global: { plugins: [router] },
    attachTo: document.body,
  })
}

// [Req-ID]: REQ-WEB-002, REQ-WEB-006, REQ-WEB-007
describe('shared generation task list', () => {
  it('loads the shared list without exposing an unsupported internal-ID filter', async () => {
    const initial = deferred<GenerationTaskPage>()
    const loadTasks = vi.fn().mockImplementationOnce(() => initial.promise)
    const wrapper = mountList(loadTasks)

    initial.resolve(firstPage)
    await flushPromises()

    expect(loadTasks).toHaveBeenNthCalledWith(1, { page: 0, size: 20 })
    expect(wrapper.text()).toContain('指定功能测试用例生成')
    expect(wrapper.get('[data-state="ready"]').text()).toContain('共 1 个任务')
    expect(wrapper.get('.task-list__heading').text()).toContain('共享任务')
    expect(wrapper.text()).not.toContain('task-old')
    expect(wrapper.find('form.task-list__filters').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps page context, filters and a keyboard-operable retry when loading fails', async () => {
    const loadTasks = vi.fn()
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce(firstPage)
    const wrapper = mountList(loadTasks)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('服务暂不可用')
    const retry = wrapper.get('button[type="button"]')
    ;(retry.element as HTMLButtonElement).focus()
    expect(document.activeElement).toBe(retry.element)
    await retry.trigger('click')
    await flushPromises()

    expect(loadTasks).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('指定功能测试用例生成')
    wrapper.unmount()
  })

  it('distinguishes the initial empty state from a loaded task list', async () => {
    const loadTasks = vi.fn()
      .mockResolvedValueOnce({ items: [], page: 0, size: 20, totalItems: 0 })
    const wrapper = mountList(loadTasks)
    await flushPromises()

    expect(wrapper.get('[data-state="empty"]').text()).toContain('尚无任务')
    wrapper.unmount()
  })

  it('shows textual status, progress and missing values without relying on color', async () => {
    const wrapper = mountList(vi.fn().mockResolvedValue({
      ...firstPage,
      items: [{ ...firstPage.items[0], status: 'PARTIAL', failureSummary: undefined, completedBatches: 2 }],
    }))
    await flushPromises()

    expect(wrapper.get('[aria-label="状态：部分完成"]').text()).toContain('部分完成')
    expect(wrapper.get('[aria-label="状态：部分完成"]').classes()).toContain('status-chip')
    expect(wrapper.text()).toContain('2 / 3 批次')
    expect(wrapper.text()).toContain('-')
    wrapper.unmount()
  })

  it('maps every active backend execution state to business wording', async () => {
    for (const status of ['AUDITING', 'GENERATING', 'VALIDATING']) {
      const wrapper = mountList(vi.fn().mockResolvedValue({
        ...firstPage,
        items: [{ ...firstPage.items[0], status }],
      }))
      await flushPromises()

      expect(wrapper.get('[aria-label^="状态："]').text()).not.toBe(status)
      wrapper.unmount()
    }
  })
})
