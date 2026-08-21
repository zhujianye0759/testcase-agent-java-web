## 1. 真实 RED 与最小修复

- [x] 1.1 使用任务 `e9e936d1-5010-4d84-8e91-5b622c618de4` 的只读 API 形状增加 TaskDetailView RED，证明旧组件错误显示完整交付、legacy 进度并隐藏 structured 结果。 `[Req-ID]: REQ-STD-001, REQ-STD-002`
- [x] 1.2 保持 structuredResult 优先投影，增加 `UNABLE_TO_GENERATE + 0 testPoints` 的明确空状态；保留 FEATURE/旧 Markdown 路径。 `[Req-ID]: REQ-STD-001~003`
- [x] 1.3 在不带入根工作树后端/规格脏改动的前提下保留当前已确认深色 PC UI。 `[Req-ID]: REQ-STD-001~003`

## 2. 静态与回归门禁

- [x] 2.1 运行 TaskDetailView 聚焦测试和全前端 unit、typecheck、lint、build。 `[Req-ID]: REQ-STD-001~003`
- [x] 2.2 运行 OpenSpec strict、git diff --check、敏感信息形态和 change-only 差异复核。 `[Req-ID]: all`

## 3. 前端部署与真实页面验证

- [ ] 3.1 从提交后的 Java Hotfix 工作树安全替换唯一 5173 前端，不修改 8082、KEE 或业务任务。 `[Req-ID]: REQ-STD-001~003`
- [ ] 3.2 在 1440x820 和相关窄宽打开同一任务，验证真实 structured 状态、阶段计数、4 条发现、1 条核对、0 用例说明和下载入口，并保存截图。 `[Req-ID]: REQ-STD-001, REQ-STD-002`
