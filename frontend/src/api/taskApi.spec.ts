import { describe, expect, it, vi } from 'vitest'

import { createTaskApi, type CreateTaskPayload } from './taskApi'

const payload: CreateTaskPayload = {
  taskMode: 'FEATURE',
  featureDescription: '用户登录',
  fewShotPolicy: 'NONE',
  schemaVersion: '2.0',
  promptVersion: '2.0',
  scopeSelectionIds: ['scope-1', 'scope-2'],
  prompt: '生成测试用例',
  workflowVersion: '2.0',
  inputVersion: '2.0',
  artifactVersion: '2.0',
  approvedFunctionScope: {
    scopeVersion: 'scope-v2',
    functions: [{
      functionKey: 'function-login', name: '用户登录', path: '账号/登录', description: '',
    }],
    testPoints: [{
      testPointKey: 'point-login-threshold',
      functionKey: 'function-login',
      type: 'BOUNDARY_VALUE',
      source: 'GENERAL_EXPERIENCE',
      status: 'PENDING_CONFIRMATION',
      description: '验证待确认的尝试次数边界',
      missingInformation: ['最大尝试次数尚未确认'],
    }],
  },
}

describe('task API client', () => {
  it('sends the frozen creation payload and derives download URLs from opaque artifact IDs only', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'task-123' }), { status: 201 }))
    const api = createTaskApi(fetcher)

    await expect(api.createTask(payload)).resolves.toEqual({ id: 'task-123' })

    expect(fetcher).toHaveBeenCalledWith('/api/tasks', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(payload),
    }))
    expect(api.artifactDownloadUrl('artifact/456')).toBe('/api/artifacts/artifact%2F456/download')
  })

  it('loads a browser-safe hierarchical scope catalog without requesting credentials', async () => {
    const scopeCatalog = {
      knowledgeBases: [{
        id: 'kb-safe', label: '战略运管知识库', systems: [{
          id: 'system-safe', label: '战略运管系统', versions: [{
            id: 'version-safe', label: 'V1.0', materialTypes: [
              { id: 'scope-1', label: '功能清单', documentCount: 1 },
            ],
          }],
        }],
      }],
    }
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      scopeCatalog,
    }), { status: 200 }))
    const api = createTaskApi(fetcher)

    await expect(api.getTaskOptions()).resolves.toEqual(scopeCatalog)
    expect(fetcher).toHaveBeenCalledWith('/api/task-options', undefined)

    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ scopeCatalog }), { status: 200 }))
    await api.getTaskOptions(true)
    expect(fetcher).toHaveBeenLastCalledWith('/api/task-options?refresh=true', undefined)
  })

  it('requests the paginated shared task list without an unsupported internal-ID query', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], page: 0, size: 20, totalItems: 0,
    }), { status: 200 }))
    const api = createTaskApi(fetcher)

    await expect(api.listTasks({ page: 0, size: 20 })).resolves.toMatchObject({
      page: 0,
      totalItems: 0,
    })

    expect(fetcher).toHaveBeenCalledWith('/api/tasks?page=0&size=20', undefined)
  })

  it('posts shared cancellation and retry actions exactly once per explicit call', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    const api = createTaskApi(fetcher)

    await api.cancelTask('task/123')
    await api.retryTask('task/123')

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/tasks/task%2F123/cancel', { method: 'POST' })
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/tasks/task%2F123/retry', { method: 'POST' })
  })
})
