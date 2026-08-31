import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import HomeView from './HomeView.vue'
import { createAppRouter } from '../router'

const singleScope = {
  knowledgeBases: [{ id: 'kb-safe', label: '战略运管知识库', systems: [{
    id: 'system-safe', label: '战略运管系统', versions: [{
      id: 'version-safe', label: 'V1.0', materialTypes: [
        { id: 'scope-1', label: '功能清单', documentCount: 2 },
      ],
    }],
  }],
  }],
}

async function mountPage(options: {
  createTask?: ReturnType<typeof vi.fn>
  loadTaskOptions?: ReturnType<typeof vi.fn>
} = {}) {
  const router = createAppRouter(createMemoryHistory())
  router.push('/')
  await router.isReady()
  const wrapper = mount(HomeView, {
    props: {
      createTask: options.createTask ?? vi.fn().mockResolvedValue({ id: 'task-123' }),
      loadTaskOptions: options.loadTaskOptions ?? vi.fn().mockResolvedValue(singleScope),
    } as never,
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
  await wrapper.get('input[name="approvedScopeVersion"]').setValue('scope-v2-fixture')
  await wrapper.get('input[name="approvedFunctionKey-0"]').setValue('function-fixture')
  await wrapper.get('input[name="approvedFunctionName-0"]').setValue('订单提交')
  await wrapper.get('input[name="approvedFunctionPath-0"]').setValue('订单/提交')
  return { wrapper, router }
}

// [Req-ID]: REQ-WEB-001, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008, REQ-WEB-009, REQ-WEB-010
describe('generation task form', () => {
  // [Req-ID]: REQ-WEB-010
  it('defaults to all generation with automatic configured-example reference and one read-only business-system scope', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    expect(wrapper.text()).toContain('生成全部测试用例')
    expect(wrapper.get('.generation-workspace__hero').text()).toContain('以材料为依据')
    // [Req-ID]: REQ-UIX-009 — hero radar visual is decorative and hidden from assistive tech
    expect(wrapper.get('.generation-workspace__hero-visual').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('[data-testid="all-mode-card"]').attributes('data-selected')).toBe('true')
    expect(wrapper.text()).toContain('自动参考优质示例（推荐）')
    expect(wrapper.text()).toContain('后台执行')
    expect(wrapper.get('.task-form__scope-value').text()).toContain('业务系统')
    expect(wrapper.get('.task-form__scope-value').text()).toContain('战略运管知识库')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('战略运管知识库')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('V1.0')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('功能清单')
    expect(wrapper.find('select[name="knowledgeBaseId"]').exists()).toBe(false)
    expect(wrapper.find('select[name="businessSystemId"]').exists()).toBe(false)
    expect(wrapper.find('select[name="systemId"]').exists()).toBe(false)
    expect(wrapper.find('input[name="featureDescription"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('FEATURE')
    expect(wrapper.text()).not.toContain('ALL')
    expect(wrapper.text()).not.toContain('Few-shot')
    expect(wrapper.text()).not.toContain('功能点 ID')

    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({
      taskMode: 'ALL',
      fewShotPolicy: 'AUTO',
      scopeSelectionIds: ['scope-1'],
      prompt: '',
      workflowVersion: '2.0',
      inputVersion: '2.0',
      artifactVersion: '2.0',
      schemaVersion: '2.0',
      promptVersion: '2.0',
      approvedFunctionScope: {
        scopeVersion: 'scope-v2-fixture',
        functions: [{
          functionKey: 'function-fixture',
          name: '订单提交',
          path: '订单/提交',
          description: '',
        }],
      },
    }))
  })

  // [Req-ID]: REQ-TGV2-001, REQ-TGV2-002
  it('fails closed without a complete versioned approved function scope and retains the entered fields', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    await wrapper.get('input[name="approvedFunctionPath-0"]').setValue('')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).not.toHaveBeenCalled()
    expect((wrapper.get('input[name="approvedScopeVersion"]').element as HTMLInputElement).value)
      .toBe('scope-v2-fixture')
  })

  it('asks for a natural-language feature description only in specified-feature mode', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    await wrapper.get('input[value="FEATURE"]').setValue(true)
    expect(wrapper.get('[data-testid="feature-mode-card"]').attributes('data-selected')).toBe('true')
    expect(wrapper.get('input[name="featureDescription"]').attributes('placeholder')).toContain('例如')
    await wrapper.get('input[name="featureDescription"]').setValue('用户登录与忘记密码')
    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({
      taskMode: 'FEATURE',
      featureDescription: '用户登录与忘记密码',
    }))
  })

  it('prevents duplicate submission and keeps the business input after a failure while focusing its summary', async () => {
    let rejectCreate: (reason?: unknown) => void = () => undefined
    const createTask = vi.fn(() => new Promise<{ id: string }>((_, reject) => {
      rejectCreate = reject
    }))
    const { wrapper } = await mountPage({ createTask })

    await wrapper.get('input[value="FEATURE"]').setValue(true)
    await wrapper.get('input[name="featureDescription"]').setValue('用户登录')
    await wrapper.get('form').trigger('submit.prevent')
    await wrapper.get('form').trigger('submit.prevent')
    expect(createTask).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[type="submit"]').text()).toContain('提交中')

    rejectCreate(new Error('服务暂不可用'))
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('服务暂不可用')
    expect((wrapper.get('input[name="featureDescription"]').element as HTMLInputElement).value).toBe('用户登录')
    expect(document.activeElement).toBe(alert.element)
  })

  it('explains an empty authorized-scope state in business language with a recovery action', async () => {
    const { wrapper } = await mountPage({ loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [] }) })

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时没有可用于生成测试用例的材料范围')
    expect(wrapper.findAll('button[type="button"]').some((button) => button.text().includes('重新加载'))).toBe(true)
    expect(wrapper.text()).not.toContain('project_id')
  })

  it('keeps the launch action discoverable while scope material is loading', async () => {
    const pendingOptions = new Promise<typeof singleScope>(() => undefined)
    const { wrapper } = await mountPage({ loadTaskOptions: vi.fn().mockReturnValue(pendingOptions) })

    expect(wrapper.get('[data-state="loading"]').text()).toContain('正在准备可用材料范围')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  // [Req-ID]: REQ-WEB-010
  it('lets users choose a business system by keyboard label without exposing a knowledge-base or system field', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [
        singleScope.knowledgeBases[0],
        { id: 'kb-safe-2', label: '营销管理知识库', systems: [{
          id: 'system-safe-2', label: '营销管理系统', versions: [{
            id: 'version-safe-2', label: 'V2.0', materialTypes: [
              { id: 'internal-scope-1', label: '功能清单', documentCount: 3 },
              { id: 'internal-scope-2', label: '工单方案', documentCount: 1 },
            ],
          }],
        }],
        },
      ] }),
    })

    const businessSystem = wrapper.get('select[name="businessSystemId"]')
    expect(businessSystem.text()).toContain('营销管理知识库')
    await businessSystem.setValue('kb-safe-2')
    expect(wrapper.find('select[name="knowledgeBaseId"]').exists()).toBe(false)
    expect(wrapper.find('select[name="systemId"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('营销管理知识库')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).not.toContain('营销管理系统')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('V2.0')
    expect(wrapper.findAll('input[name="materialTypeIds"]')).toHaveLength(2)
    await wrapper.get('input[value="internal-scope-1"]').setValue(true)
    await wrapper.get('input[value="internal-scope-2"]').setValue(true)
    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({
      scopeSelectionIds: ['internal-scope-1', 'internal-scope-2'],
    }))
    expect(wrapper.text()).not.toContain('internal-scope-1')
    expect(wrapper.text()).not.toContain('UUID')
  })

  // [Req-ID]: REQ-WEB-010
  it('clears invalid version and material selections when the user changes the business system', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [
        { ...singleScope.knowledgeBases[0], systems: [{
          ...singleScope.knowledgeBases[0].systems[0],
          versions: [
            { ...singleScope.knowledgeBases[0].systems[0].versions[0], materialTypes: [
              { id: 'scope-1', label: '功能清单', documentCount: 1 },
              { id: 'scope-2', label: '工单方案', documentCount: 1 },
            ] },
            { id: 'version-old', label: 'V0.9', materialTypes: [{ id: 'scope-old', label: '旧版说明', documentCount: 1 }] },
          ],
        }] },
        { id: 'kb-safe-2', label: '营销管理知识库', systems: [{
          id: 'system-safe-2', label: '营销管理系统', versions: [{
            id: 'version-safe-2', label: 'V2.0', materialTypes: [{ id: 'scope-3', label: '需求规格说明书', documentCount: 1 }],
          }],
        }] },
      ] }),
    })
    await wrapper.get('select[name="businessSystemId"]').setValue('kb-safe')
    await wrapper.get('select[name="versionId"]').setValue('version-safe')
    await wrapper.get('input[value="scope-1"]').setValue(true)
    await wrapper.get('select[name="businessSystemId"]').setValue('kb-safe-2')

    expect(wrapper.find('input[value="scope-1"]').exists()).toBe(false)
    expect(wrapper.find('option[value="version-safe"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('需求规格说明书')
    await wrapper.get('form').trigger('submit.prevent')
    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({ scopeSelectionIds: ['scope-3'] }))
  })

  // [Req-ID]: REQ-FSC-006
  it('submits individually selected documents instead of widening to every document of the same type', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [{
        id: 'kb-safe', label: '梅州知识库', systems: [{
          id: 'system-safe', label: '电表智能验收应用', versions: [{
            id: 'version-safe', label: 'V1.0', materialTypes: [
              {
                id: 'legacy-function-list', label: '功能清单', documentCount: 1,
                documents: [{ id: 'document-function-list', label: '应用功能清单.xlsx' }],
              },
              {
                id: 'legacy-prototype', label: '界面原型图', documentCount: 2,
                documents: [
                  { id: 'document-prototype-selected', label: '一图：界面原型图.docx' },
                  { id: 'document-prototype-fixture', label: '1页PDF.pdf' },
                ],
              },
            ],
          }],
        }],
      }] }),
    })

    expect(wrapper.findAll('input[name="materialDocumentIds"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('应用功能清单.xlsx')
    expect(wrapper.text()).toContain('一图：界面原型图.docx')
    expect(wrapper.text()).not.toContain('document-prototype-selected')
    expect(wrapper.text()).not.toContain('legacy-prototype')
    await wrapper.get('input[value="document-function-list"]').setValue(true)
    await wrapper.get('input[value="document-prototype-selected"]').setValue(true)
    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({
      scopeSelectionIds: ['document-function-list', 'document-prototype-selected'],
    }))
    expect(createTask).not.toHaveBeenCalledWith(expect.objectContaining({
      scopeSelectionIds: expect.arrayContaining(['legacy-prototype']),
    }))
  })

  // [Req-ID]: REQ-FSC-006
  it('uses a legacy aggregate selection only when the backend omits document leaves', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [{
        id: 'kb-safe', label: '历史知识库', systems: [{
          id: 'system-safe', label: '历史系统', versions: [{
            id: 'version-safe', label: 'V1.0', materialTypes: [
              { id: 'legacy-only-scope', label: '工单方案', documentCount: 1 },
            ],
          }],
        }],
      }] }),
    })

    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({ scopeSelectionIds: ['legacy-only-scope'] }))
  })

  // [Req-ID]: REQ-FSC-006
  it('fails closed when a current backend explicitly reports no selectable documents', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [{
        id: 'kb-safe', label: '梅州知识库', systems: [{
          id: 'system-safe', label: '电表智能验收应用', versions: [{
            id: 'version-safe', label: 'V1.0', materialTypes: [
              { id: 'must-not-fallback', label: '界面原型图', documentCount: 0, documents: [] },
            ],
          }],
        }],
      }] }),
    })

    expect(wrapper.text()).toContain('当前材料范围没有可选择的已完成文档')
    expect(wrapper.find('input[value="must-not-fallback"]').exists()).toBe(false)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).not.toHaveBeenCalled()
  })

  // [Req-ID]: REQ-FSC-006
  it('renders the server-provided Chinese material label and safe file name without internal identifiers', async () => {
    const { wrapper } = await mountPage({
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [{
        id: 'kb-safe', label: '梅州知识库', systems: [{
          id: 'system-safe', label: '电表智能验收应用', versions: [{
            id: 'version-safe', label: 'V1.0', materialTypes: [{
              id: 'scope-safe', label: '未命名材料类型', documentCount: 1,
              documents: [{ id: 'document-safe', label: '应用功能清单.xlsx' }],
            }],
          }],
        }],
      }] }),
    })

    expect(wrapper.text()).toContain('未命名材料类型')
    expect(wrapper.text()).toContain('应用功能清单.xlsx')
    expect(wrapper.text()).not.toContain('work_order_plan')
    expect(wrapper.text()).not.toContain('document-safe')
  })

})
