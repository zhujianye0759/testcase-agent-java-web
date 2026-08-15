import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import HomeView from './HomeView.vue'
import { createAppRouter } from '../router'

const singleScope = [{ id: 'scope-1', label: '战略运管 V1.0 准入材料' }]

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

// [Req-ID]: REQ-WEB-001, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008
describe('generation task form', () => {
  it('defaults to all generation with automatic configured-example reference and one read-only business scope', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    expect(wrapper.text()).toContain('生成全部测试用例')
    expect(wrapper.text()).toContain('自动参考优质示例（推荐）')
    expect(wrapper.text()).toContain('后台执行')
    expect(wrapper.get('[data-testid="scope-summary"]').text()).toContain('战略运管 V1.0 准入材料')
    expect(wrapper.find('select[name="scopeOptionId"]').exists()).toBe(false)
    expect(wrapper.find('input[name="featureDescription"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('FEATURE')
    expect(wrapper.text()).not.toContain('ALL')
    expect(wrapper.text()).not.toContain('Few-shot')
    expect(wrapper.text()).not.toContain('功能点 ID')

    await wrapper.get('form').trigger('submit.prevent')

    expect(createTask).toHaveBeenCalledWith(expect.objectContaining({
      taskMode: 'ALL',
      fewShotPolicy: 'AUTO',
      scopeOptionId: 'scope-1',
      prompt: '',
    }))
  })

  it('asks for a natural-language feature description only in specified-feature mode', async () => {
    const createTask = vi.fn().mockResolvedValue({ id: 'task-123' })
    const { wrapper } = await mountPage({ createTask })

    await wrapper.get('input[value="FEATURE"]').setValue(true)
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
    const { wrapper } = await mountPage({ loadTaskOptions: vi.fn().mockResolvedValue([]) })

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时没有可用于生成测试用例的材料范围')
    expect(wrapper.get('button[type="button"]').text()).toContain('重新加载')
    expect(wrapper.text()).not.toContain('project_id')
  })

  it('shows only business labels when several authorized scopes are available', async () => {
    const { wrapper } = await mountPage({
      loadTaskOptions: vi.fn().mockResolvedValue([
        { id: 'internal-scope-1', label: '战略运管 V1.0 准入材料' },
        { id: 'internal-scope-2', label: '营销管理 V2.0 准入材料' },
      ]),
    })

    expect(wrapper.get('select[name="scopeOptionId"]').text()).toContain('战略运管 V1.0 准入材料')
    expect(wrapper.text()).not.toContain('internal-scope-1')
    expect(wrapper.text()).not.toContain('知识库')
    expect(wrapper.text()).not.toContain('UUID')
  })
})
