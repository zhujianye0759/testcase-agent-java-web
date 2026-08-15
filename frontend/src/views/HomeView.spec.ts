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
  return { wrapper, router }
}

// [Req-ID]: REQ-WEB-001, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008, REQ-WEB-009
describe('generation task form', () => {
  it('defaults to all generation with automatic configured-example reference and one read-only business scope', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    expect(wrapper.text()).toContain('生成全部测试用例')
    expect(wrapper.get('.generation-workspace__hero').text()).toContain('以材料为依据')
    expect(wrapper.get('[data-testid="all-mode-card"]').attributes('data-selected')).toBe('true')
    expect(wrapper.text()).toContain('自动参考优质示例（推荐）')
    expect(wrapper.text()).toContain('后台执行')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('战略运管知识库')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('V1.0')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('功能清单')
    expect(wrapper.find('select[name="knowledgeBaseId"]').exists()).toBe(false)
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
    }))
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
    expect(wrapper.get('button[type="button"]').text()).toContain('重新加载')
    expect(wrapper.text()).not.toContain('project_id')
  })

  it('keeps the launch action discoverable while scope material is loading', async () => {
    const pendingOptions = new Promise<typeof singleScope>(() => undefined)
    const { wrapper } = await mountPage({ loadTaskOptions: vi.fn().mockReturnValue(pendingOptions) })

    expect(wrapper.get('[data-state="loading"]').text()).toContain('正在准备可用材料范围')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('lets users select business knowledge, system, version and one or more material types', async () => {
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

    await wrapper.get('select[name="knowledgeBaseId"]').setValue('kb-safe-2')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('营销管理系统')
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

  it('clears invalid lower selections when the user changes the knowledge base', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({
      createTask,
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [
        { ...singleScope.knowledgeBases[0], systems: [{
          ...singleScope.knowledgeBases[0].systems[0],
          versions: [{ ...singleScope.knowledgeBases[0].systems[0].versions[0], materialTypes: [
            { id: 'scope-1', label: '功能清单', documentCount: 1 },
            { id: 'scope-2', label: '工单方案', documentCount: 1 },
          ] }],
        }] },
        { id: 'kb-safe-2', label: '营销管理知识库', systems: [{
          id: 'system-safe-2', label: '营销管理系统', versions: [{
            id: 'version-safe-2', label: 'V2.0', materialTypes: [{ id: 'scope-3', label: '需求规格说明书', documentCount: 1 }],
          }],
        }] },
      ] }),
    })
    await wrapper.get('select[name="knowledgeBaseId"]').setValue('kb-safe')
    await wrapper.get('input[value="scope-1"]').setValue(true)
    await wrapper.get('select[name="knowledgeBaseId"]').setValue('kb-safe-2')

    expect(wrapper.find('input[value="scope-1"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('需求规格说明书')
    await wrapper.get('form').trigger('submit.prevent')
    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({ scopeSelectionIds: ['scope-3'] }))
  })

  it('offers system and version selectors when the catalog has more than one business option', async () => {
    const { wrapper } = await mountPage({
      loadTaskOptions: vi.fn().mockResolvedValue({ knowledgeBases: [{
        id: 'kb-safe', label: '综合管理知识库', systems: [
          { id: 'system-a', label: '战略运管系统', versions: [
            { id: 'version-a1', label: 'V1.0', materialTypes: [{ id: 'scope-a1', label: '功能清单', documentCount: 1 }] },
            { id: 'version-a2', label: 'V2.0', materialTypes: [{ id: 'scope-a2', label: '工单方案', documentCount: 1 }] },
          ] },
          { id: 'system-b', label: '营销管理系统', versions: [
            { id: 'version-b1', label: 'V3.0', materialTypes: [{ id: 'scope-b1', label: '需求规格说明书', documentCount: 1 }] },
          ] },
        ],
      }] }),
    })

    expect(wrapper.find('select[name="knowledgeBaseId"]').exists()).toBe(false)
    await wrapper.get('select[name="systemId"]').setValue('system-a')
    await wrapper.get('select[name="versionId"]').setValue('version-a2')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('V2.0')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('工单方案')

    await wrapper.get('select[name="systemId"]').setValue('system-b')
    expect(wrapper.find('select[name="versionId"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('营销管理系统')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('V3.0')
  })
})
