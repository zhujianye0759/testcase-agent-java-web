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
  businessProgress: {
    currentBusinessStage: '已完成',
    materialDocumentTotal: 2,
    completeMaterialDocumentCount: 2,
    materialUnitTotal: 4,
    processedMaterialUnitCount: 4,
    totalAuditWork: 8,
    completedAuditWork: 8,
    failedAuditWork: 0,
    featureCandidateTotal: 6,
    functionListMissingCount: 0,
    requirementMissingCount: 0,
    conflictCount: 0,
    splitCount: 0,
    mergeCount: 0,
    insufficientEvidenceCount: 0,
    frozenComplete: true,
    frozenFeatureTotal: 5,
    generationEligibleFrozenFeatureCount: 4,
    generationIneligibleFrozenFeatureCount: 1,
    expectedTestCaseTotal: 8,
    acceptedTestCaseCount: 8,
    coverageStatus: '完整',
    businessReason: '材料审查、功能冻结和测试用例均已完成',
  },
}

// [Req-ID]: REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008
describe('generation task detail', () => {
  it('[Req-ID]: REQ-SGD-001, REQ-SGD-002 renders saved structured results with processing and coverage separated', async () => {
    const wrapper = mount(TaskDetailView, {
      props: {
        taskId: 'task-structured',
        getTask: vi.fn().mockResolvedValue({
          ...completedDetail,
          status: 'PARTIAL',
          auditRows: [{ sequence: 99, subjectOrFeature: '旧 Markdown 行', issueCategory: '不应显示', evidenceComparison: 'raw' }],
          testCaseRows: [{ ...completedDetail.testCaseRows[0], caseName: '旧 Markdown 用例' }],
          structuredResult: {
            processingStatus: 'COMPLETED',
            coverageStatus: 'PARTIAL',
            pendingCandidateCaseCount: 1,
            reviewFindings: [{
              sourceLabel: '需求规格说明书', subject: '订单提交', issueType: '边界未说明', description: '未说明最大订单金额。',
              handlingLevel: 'CONTINUE_INCOMPLETE', testDesignImpact: '边界用例待确认', currentProjectRecommendation: '确认最大金额',
              designCenterGuidelineRecommendation: '补充金额边界模板',
            }],
            reconciliations: [{
              functionListPaths: ['订单/提交'], requirementFunctions: ['提交订单'], classification: 'exact_match',
              scopeRecommendation: '纳入本次测试范围', confirmationStatus: 'CONFIRMED',
            }],
            testPoints: [{
              functionName: '提交订单', type: 'boundary_value', description: '最大金额边界', basis: 'GENERAL_EXPERIENCE',
              missingInformation: ['需求未给出最大金额'], formalCoverageSatisfied: false,
              testcases: [{
                title: '订单金额上限候选', status: 'PENDING_CONFIRMATION', preconditions: ['已登录'],
                steps: [{ stepNo: 1, action: '输入候选上限', expected: '系统按确认后的规则处理' }],
                requirementSummaries: [], missingInformation: ['需求未给出最大金额'],
              }],
            }],
          },
        }),
      } as never,
    })
    await flushPromises()

    const result = wrapper.get('[data-testid="structured-result"]')
    expect(wrapper.text()).toContain('处理状态')
    expect(wrapper.text()).toContain('正式覆盖部分完整')
    expect(wrapper.text()).toContain('待确认候选')
    expect(result.text()).toContain('未说明最大订单金额')
    expect(result.text()).toContain('纳入本次测试范围')
    expect(wrapper.text()).toContain('订单金额上限候选 · 待确认候选')
    expect(wrapper.text()).not.toContain('旧 Markdown 行')
    expect(wrapper.text()).not.toContain('旧 Markdown 用例')
    expect(wrapper.text()).not.toContain('item_key')
    expect(wrapper.text()).not.toContain('evidence_keys')
  })

  it('[Req-ID]: REQ-SGD-002 does not expose an unknown structured enum as reader-facing text', async () => {
    const wrapper = mount(TaskDetailView, {
      props: {
        taskId: 'task-unknown-structured-status',
        getTask: vi.fn().mockResolvedValue({
          ...completedDetail,
          structuredResult: {
            processingStatus: 'PARTIAL', coverageStatus: 'INSUFFICIENT', pendingCandidateCaseCount: 0,
            reviewFindings: [], reconciliations: [], testPoints: [],
          },
        }),
      } as never,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('处理状态不可用')
    expect(wrapper.text()).toContain('覆盖状态不可用')
    expect(wrapper.text()).not.toContain('INSUFFICIENT')
    wrapper.unmount()
  })

  it('[Req-ID]: REQ-WEB-003, REQ-WEB-005 renders a business-readable result summary and lets the response name the download', async () => {
    const getTask = vi.fn().mockResolvedValue(completedDetail)
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-123', getTask } as never,
    })

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    expect(wrapper.find('a[href*="/download"]').exists()).toBe(false)
    await flushPromises()

    const download = wrapper.get('a[href*="/download"]')
    expect(download.classes()).toContain('task-detail__download-action')
    expect(download.attributes('href')).toBe('/api/artifacts/artifact-456/download')
    expect(download.attributes()).not.toHaveProperty('download')
    expect(wrapper.get('[aria-label="状态：已完成"]').text()).toContain('已完成')
    expect(wrapper.get('[aria-label="状态：已完成"]').classes()).toContain('status-chip')
    expect(wrapper.find('[data-state="ready"]').exists()).toBe(true)
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

  it('[Req-ID]: REQ-CWR-001 presents the business stage without treating candidates as frozen features', async () => {
    const wrapper = mount(TaskDetailView, {
      props: {
        taskId: 'task-auditing',
        getTask: vi.fn().mockResolvedValue({
          ...completedDetail,
          status: 'AUDITING',
          artifactReady: false,
          artifactId: undefined,
          businessProgress: {
            ...completedDetail.businessProgress,
            currentBusinessStage: '需求扫描（第一遍）',
            materialDocumentTotal: 3,
            completeMaterialDocumentCount: 2,
            materialUnitTotal: 5,
            processedMaterialUnitCount: 3,
            totalAuditWork: 8,
            completedAuditWork: 4,
            featureCandidateTotal: 7,
            frozenComplete: false,
            frozenFeatureTotal: null,
            generationEligibleFrozenFeatureCount: null,
            generationIneligibleFrozenFeatureCount: null,
            expectedTestCaseTotal: null,
            acceptedTestCaseCount: 0,
            coverageStatus: '进行中',
            businessReason: '正在处理材料、审查或测试用例',
          },
        }),
      } as never,
    })
    await flushPromises()

    const progress = wrapper.get('[data-testid="business-progress"]')
    expect(progress.text()).toContain('当前阶段：需求扫描（第一遍）')
    expect(progress.text()).toContain('2 / 3 份')
    expect(progress.text()).toContain('3 / 5 个材料单元')
    expect(progress.text()).toContain('4 / 8 项审查工作')
    expect(progress.text()).toContain('审查候选 7 项（尚非最终功能数）')
    expect(progress.text()).toContain('尚未冻结')
    expect(progress.text()).not.toContain('已冻结 7 个功能')
    expect(progress.text()).not.toContain('预计 8 条测试用例')
  })

  it('[Req-ID]: REQ-CWR-002 distinguishes complete delivery, partial delivery, audit failure, and cancellation', async () => {
    const expectations = [
      ['COMPLETED', true, '完整交付'],
      ['PARTIAL', true, '审查已完成，测试用例不完整'],
      ['FAILED', false, '材料或审查失败'],
      ['CANCELLED', false, '任务已取消'],
    ] as const

    for (const [status, frozenComplete, message] of expectations) {
      const wrapper = mount(TaskDetailView, {
        props: {
          taskId: `task-${status}`,
          getTask: vi.fn().mockResolvedValue({
            ...completedDetail,
            status,
            businessProgress: {
              ...completedDetail.businessProgress,
              frozenComplete,
              coverageStatus: status === 'FAILED' ? '材料或审查失败' : completedDetail.businessProgress.coverageStatus,
            },
          }),
        } as never,
      })
      await flushPromises()

      expect(wrapper.get('[data-testid="delivery-status"]').text()).toContain(message)
      wrapper.unmount()
    }
  })

  it('[Req-ID]: REQ-CWR-001 ignores stale detail responses after task changes and repeated loads', async () => {
    let resolveFirst: (value: typeof completedDetail) => void = () => undefined
    let resolveMiddle: (value: typeof completedDetail) => void = () => undefined
    let resolveLatest: (value: typeof completedDetail) => void = () => undefined
    const first = new Promise<typeof completedDetail>((resolve) => { resolveFirst = resolve })
    const middle = new Promise<typeof completedDetail>((resolve) => { resolveMiddle = resolve })
    const latest = new Promise<typeof completedDetail>((resolve) => { resolveLatest = resolve })
    const getTask = vi.fn()
      .mockReturnValueOnce(first)
      .mockReturnValueOnce(middle)
      .mockReturnValueOnce(latest)
    const wrapper = mount(TaskDetailView, {
      props: { taskId: 'task-first', getTask } as never,
    })

    await wrapper.setProps({ taskId: 'task-middle' })
    await wrapper.setProps({ taskId: 'task-first' })
    expect(getTask).toHaveBeenCalledTimes(3)

    resolveLatest({ ...completedDetail, testCaseRows: [{ ...completedDetail.testCaseRows[0], caseName: '最新任务结果' }] })
    await flushPromises()
    resolveMiddle({ ...completedDetail, testCaseRows: [{ ...completedDetail.testCaseRows[0], caseName: '过期的中间结果' }] })
    resolveFirst({ ...completedDetail, testCaseRows: [{ ...completedDetail.testCaseRows[0], caseName: '过期的首个结果' }] })
    await flushPromises()

    expect(wrapper.text()).toContain('最新任务结果')
    expect(wrapper.text()).not.toContain('过期的中间结果')
    expect(wrapper.text()).not.toContain('过期的首个结果')
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

  it('[Req-ID]: REQ-SGD-002 distinguishes forbidden and not-found detail states', async () => {
    for (const [message, state] of [['任务请求失败（403）', 'forbidden'], ['任务请求失败（404）', 'not-found']] as const) {
      const wrapper = mount(TaskDetailView, {
        props: { taskId: `task-${state}`, getTask: vi.fn().mockRejectedValue(new Error(message)) } as never,
      })
      await flushPromises()

      expect(wrapper.get('[role="alert"]').attributes('data-state')).toBe(state)
      wrapper.unmount()
    }
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
