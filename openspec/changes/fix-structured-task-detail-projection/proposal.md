## Why

真实结构化 ALL 任务已经返回并持久化 `COMPLETED + UNABLE_TO_GENERATE`、四阶段计数、4 条审查发现和 1 条待确认核对，但当前 5173 前端来自缺少 structured 投影的旧工作树，仍用 legacy `businessProgress` 显示“完整交付”、旧 0/5 审查进度和“功能尚未冻结”。这会向用户错误陈述处理结果，并隐藏已经保存的结构化数据。

## What Changes

- 以真实任务 `e9e936d1-5010-4d84-8e91-5b622c618de4` 的详情 API 形状建立前端回归夹具。
- structured ALL 存在 `structuredResult` 时优先显示独立的处理状态、覆盖结果和四阶段计数，不渲染 legacy `businessProgress`、Markdown 审查行或 Markdown 用例行。
- 显示已保存的审查发现与核对结果；当覆盖为 `UNABLE_TO_GENERATE` 且没有测试点时，明确说明没有已确认范围可用于生成正式用例。
- 保留 FEATURE 和旧 Markdown 任务的原详情路径、加载/错误/空状态与下载行为。
- 从包含正确 structured 投影且可复现的 Java Web 工作树构建并部署前端，同时保留当前已确认的深色 PC 页面表现。

## Capabilities

### New Capabilities

- `structured-task-detail-projection`: 定义结构化 ALL 任务详情优先级、状态语义、空用例说明和 legacy 兼容边界。

### Modified Capabilities

无。

## Impact

- 前端：`TaskDetailView.vue`、`TaskDetailView.spec.ts` 及现有语义样式映射。
- 部署：只替换 5173 前端进程/构建来源，不修改 8082 Java 后端、KEE、数据库或业务任务。
- API/数据：无字段、接口、持久化或迁移变更。
