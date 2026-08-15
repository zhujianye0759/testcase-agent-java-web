import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import TaskDetailView from './TaskDetailView.vue'

const completedDetail = {
  id: 'task-123',
  taskMode: 'ALL' as const,
  status: 'COMPLETED',
  totalBatches: 2,
  completedBatches: 2,
  artifactReady: true,
  artifactId: 'artifact-456',
  frozenScope: {
    state: 'FROZEN' as const,
    materialCategory: 'admission_material',
    admissionType: 'requirements_spec',
    documentCount: 2,
  },
  batches: [{ featureId: 'feature-login', status: 'ACCEPTED', failureSummary: undefined }],
  auditRows: [{
    sequence: 1, subjectOrFeature: '用户登录', issueCategory: '描述质量问题', evidenceComparison: '登录失败后的提示方式未明确。',
  }],
  testCaseRows: [{
    caseName: '用户正常登录', featureModule: '用户登录', preconditions: '已创建有效账号',
    executionSteps: '1. 输入有效账号和密码', expectedResult: '成功进入首页', requirementContent: '登录功能说明',
  }],
}

// [Req-ID]: REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008
describe('generation task detail', () => {
  it('[Req-ID]: REQ-WEB-003, REQ-WEB-005 renders a business-readable result summary and lets the response name the download', async () => {
    const getTask = vi.fn().mockResolvedValue(completedDetail)
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-123', getTask } as never,
    })

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    expect(wrapper.find('a[href*="/download"]').exists()).toBe(false)
    await flushPromises()

    const download = wrapper.get('a[href*="/download"]')
    expect(download.attributes('href')).toBe('/api/artifacts/artifact-456/download')
    expect(download.attributes()).not.toHaveProperty('download')
    expect(wrapper.get('[aria-label="状态：已完成"]').text()).toContain('已完成')
    expect(wrapper.text()).toContain('本次材料范围')
    expect(wrapper.text()).toContain('用户正常登录')
    expect(wrapper.text()).toContain('本次材料范围')
    expect(wrapper.text()).toContain('描述质量问题')
    expect(wrapper.text()).toContain('登录失败后的提示方式未明确。')
    expect(wrapper.text()).toContain('已创建有效账号')
    expect(wrapper.text()).toContain('成功进入首页')
    expect(wrapper.text()).not.toContain('FROZEN')
    expect(wrapper.text()).not.toContain('task-123')
    expect(wrapper.text()).not.toContain('feature-login')
    expect(wrapper.text()).not.toContain('校验标识')
    expect(wrapper.text()).not.toContain('来源追溯')
    expect(wrapper.text()).not.toContain('示例参考情况')
    expect(wrapper.text()).toContain('审查发现')
    expect(wrapper.text()).toContain('测试用例')
    expect(wrapper.text()).not.toContain('Few-shot')
    expect(wrapper.text()).not.toContain('AUTO')
    expect(wrapper.text()).not.toContain('artifactPath')
    expect(wrapper.text()).not.toContain('requirement-kb')
  })

  it('renders running, partial and failed states with textual progress and missing values', async () => {
    for (const status of ['RUNNING', 'PARTIAL', 'FAILED']) {
      const wrapper = mount(TaskDetailView, {
        props: {
          taskId: `task-${status}`,
          getTask: vi.fn().mockResolvedValue({
            ...completedDetail,
            status,
            completedBatches: 0,
            artifactReady: false,
            artifactId: undefined,
            batches: [],
            auditRows: [],
            testCaseRows: [],
          }),
        } as never,
      })
      await flushPromises()

      expect(wrapper.text()).toContain('0 / 2 个批次已完成')
      expect(wrapper.text()).toContain('-')
      expect(wrapper.find('a[download]').exists()).toBe(false)
      wrapper.unmount()
    }
  })

  it('keeps context and offers a keyboard-operable retry when the local detail region fails', async () => {
    const getTask = vi.fn()
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce(completedDetail)
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-123', getTask } as never,
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('生成任务详情')
    expect(wrapper.text()).not.toContain('task-123')
    const retry = wrapper.get('button')
    retry.element.focus()
    expect(document.activeElement).toBe(retry.element)
    await retry.trigger('click')
    await flushPromises()

    expect(getTask).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('用户登录')
    wrapper.unmount()
  })

  it('keeps the full accumulated business rows accessible through native details controls without exposing internal evidence fields', async () => {
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-123', getTask: vi.fn().mockResolvedValue(completedDetail) } as never,
    })
    await flushPromises()

    const details = wrapper.findAll('.task-detail__cards details')
    expect(details).toHaveLength(2)
    await details[0].get('summary').trigger('click')
    expect(details[0].text()).toContain('登录失败后的提示方式未明确。')
    await details[1].get('summary').trigger('click')
    expect(details[1].text()).toContain('已创建有效账号')
    expect(details[1].text()).toContain('成功进入首页')
    expect(wrapper.text()).not.toContain('可信程度')
    expect(wrapper.text()).not.toContain('证据数')
  })

  it('requires an explicit cancellation confirmation, prevents duplicate action, and restores focus on dismissal', async () => {
    let resolveCancel: () => void = () => undefined
    const cancelTask = vi.fn(() => new Promise<void>((resolve) => {
      resolveCancel = resolve
    }))
    const getTask = vi.fn()
      .mockResolvedValueOnce({ ...completedDetail, status: 'RUNNING', artifactReady: false, artifactId: undefined })
      .mockResolvedValueOnce({ ...completedDetail, status: 'CANCELLED', artifactReady: false, artifactId: undefined })
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-123', getTask, cancelTask } as never,
      attachTo: document.body,
    })
    await flushPromises()

    const cancel = wrapper.get('button.task-detail__cancel-action')
    await cancel.trigger('click')
    expect(cancelTask).not.toHaveBeenCalled()
    expect(wrapper.get('dialog').text()).toContain('确认取消任务')

    await wrapper.get('dialog button:first-of-type').trigger('click')
    expect(document.activeElement).toBe(cancel.element)

    await cancel.trigger('click')
    const confirm = wrapper.get('dialog button:last-of-type')
    await confirm.trigger('click')
    await confirm.trigger('click')
    expect(cancelTask).toHaveBeenCalledTimes(1)
    expect((confirm.element as HTMLButtonElement).disabled).toBe(true)

    resolveCancel()
    await flushPromises()
    expect(getTask).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已取消')
    wrapper.unmount()
  })

  it('only offers retry for failed batches and retains dialog context when the write fails', async () => {
    const retryTask = vi.fn().mockRejectedValue(new Error('重试服务不可用'))
    const wrapper = mount(TaskDetailView, {
      props: {
        taskId: 'task-123',
        getTask: vi.fn().mockResolvedValue({
          ...completedDetail,
          status: 'PARTIAL',
          artifactReady: false,
          artifactId: undefined,
          batches: [{ featureId: 'feature-login', status: 'FAILED', failureSummary: '暂时失败' }],
        }),
        retryTask,
      } as never,
    })
    await flushPromises()

    expect(wrapper.find('button.task-detail__cancel-action').exists()).toBe(false)
    const retry = wrapper.get('button:not(.task-detail__cancel-action)')
    expect(retry.text()).toContain('重试失败批次')
    await retry.trigger('click')
    await wrapper.get('dialog button:last-of-type').trigger('click')
    await flushPromises()

    expect(retryTask).toHaveBeenCalledTimes(1)
    expect(wrapper.get('dialog [role="alert"]').text()).toContain('重试服务不可用')
    expect(wrapper.text()).toContain('暂时失败')
  })

  it('offers a business retry when all-function discovery fails before any batch is created', async () => {
    const retryTask = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TaskDetailView, {
      props: {
        taskId: 'task-discovery-failed',
        getTask: vi.fn().mockResolvedValue({
          ...completedDetail,
          status: 'FAILED',
          artifactReady: false,
          artifactId: undefined,
          batches: [],
          failureSummary: '材料暂时无法识别',
        }),
        retryTask,
      } as never,
    })
    await flushPromises()

    const retry = wrapper.get('button')
    expect(retry.text()).toContain('重新识别并生成')
    await retry.trigger('click')
    expect(wrapper.get('dialog').text()).toContain('重新识别材料并生成测试用例')
    expect(wrapper.get('dialog').text()).not.toContain('批次')
  })
})
