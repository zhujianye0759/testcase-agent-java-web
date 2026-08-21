## Context

Java 详情 API 已正确同时返回 legacy `businessProgress` 和新的 `structuredResult`。legacy 字段用于 FEATURE/旧 Markdown 任务；structured ALL 必须使用已验收持久化投影。现场 5173 由根工作树启动，该工作树的详情组件没有 structured 分支，因此顶层 `COMPLETED` 被旧文案直接解释为“完整交付”。正确 structured 投影已存在于当前 Java Hotfix 分支，但缺少本次 `UNABLE_TO_GENERATE + 0 testPoints` 的真实回归夹具，且尚未从该分支部署前端。

## Goals / Non-Goals

**Goals:**

- 通过 `TaskDetailView` 公共组件接缝锁定 structured 优先与 legacy 隔离。
- 分别呈现 processing 与 coverage；显示四阶段真实计数、审查发现、核对结果和 0 用例原因。
- 保留当前 PC 深色页面的语义 token、布局和可访问状态。
- 构建、部署后用同一只读任务进行真实页面验证。

**Non-Goals:**

- 不修改 KEE、Java API、数据库、Excel或现有任务。
- 不猜测下一版高粒度用例、评审示例或 Excel 字段。
- 不创建或重试业务任务。

## Decisions

### 1. `structuredResult` 是 structured ALL 的唯一页面状态来源

组件只要收到 `structuredResult`，状态、进度、审查、核对和用例区域就全部读取该对象；legacy `businessProgress`、`auditRows` 和 `testCaseRows` 不参与 structured 页面。没有 `structuredResult` 时继续走旧路径。

### 2. 0 测试点按 coverage 给出业务含义

`COMPLETED + UNABLE_TO_GENERATE + testPoints=[]` 显示“处理已完成，但没有可用于生成正式用例的已确认功能范围，因此未生成测试用例”。这不是加载中、失败或完整交付，也不把 0 当作缺失数据。

### 3. 以真实 API 夹具证明部署分支

同一个回归测试先在现场旧组件上稳定失败，再在当前 Hotfix 组件上通过。部署从提交后的 Hotfix 前端构建，避免继续由缺少 structured 代码的根工作树运行；现有深色 UI 只按已存在的 token/样式迁入，不改变业务结构。

## Risks / Trade-offs

- **深色 UI 与 structured 模板来自不同在制分支** → 只迁移前端语义样式文件和详情页样式，逐文件复核，不带入根工作树后端或 OpenSpec 脏改动。
- **0 用例被误认为错误** → 同时显示处理已完成、覆盖无法生成和明确原因，保留 Excel 下载入口。
- **旧任务回归** → 继续运行现有 FEATURE/Markdown 页面测试，并断言无 `structuredResult` 时仍显示 legacy 结果。

## Migration Plan

先提交并构建前端，再只读核对 5173 PID/命令行；停止唯一旧 5173 进程并从提交工作树启动同端口，使用当前任务只读验证。回滚时可从原根工作树重新启动旧前端，不涉及数据回滚。

## Open Questions

无。新的 KEE 字段保持等待冻结。
