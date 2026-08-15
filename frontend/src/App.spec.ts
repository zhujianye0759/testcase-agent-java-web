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

    expect(wrapper.get('nav[aria-label="主导航"]').text()).toContain('测试用例智能生成')
    expect(wrapper.get('main h1').text()).toBe('测试用例生成')
  })
})
