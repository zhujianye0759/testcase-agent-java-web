import { mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'

import App from './App.vue'
import { createAppRouter } from './router'

describe('application shell', () => {
  it('presents the generation workspace through the public router entry', async () => {
    const router = createAppRouter(createMemoryHistory())
    router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    })

    expect(wrapper.get('.app-shell__brand').text()).toContain('测试用例智能生成')
    expect(wrapper.get('nav[aria-label="主导航"]').text()).toContain('开始生成')
    expect(wrapper.get('.app-shell__service-context').text()).toContain('后台执行 · 任务可追踪')
    expect(wrapper.get('.app-shell__service-context').attributes('role')).toBeUndefined()
    expect(wrapper.get('.app-shell__service-context').attributes('data-testid')).toBeUndefined()
    expect(wrapper.get('a.app-shell__nav-link[aria-current="page"]').text()).toContain('开始生成')
    expect(wrapper.get('main h1').text()).toBe('测试用例生成')
  })
})
