## Why

现有 Java 结构化生成链路把材料质量评审和功能范围核对作为生成前置门禁，导致材料表达不规范时整个任务无法产出测试用例，也继续依赖已经退场的 KEE V1 operation。KEE version 2 第一阶段合同已经冻结，Java 需要迁移为“已审核功能范围 + 需求事实提取 + 测试用例设计”的版本化生成链路，同时保留历史任务只读兼容。

## What Changes

- 新增 `contract_version=2.0` 的任务输入、工作流和制品版本，所有新任务只调用 `requirement-fact-extraction` 与 `functional-testcase-design`。
- 新增两类 KEE V2 专用请求/响应 DTO、HTTP 状态与固定 `error.details.type` 解析，以及 Java 权威业务校验。
- 新增已审核功能范围冻结、事实稳定键/跨窗口合并、测试可测性反馈、正式/待确认/无法生成结果和分轴状态持久化。
- 调整新任务编排，移除材料质量评审、功能候选提取和功能对账作为生成前置门禁；按函数、材料窗口和测试点局部继续，任何未校验窗口不得部分发布正式数据。
- 扩展详情 API、中文页面和固定两张 Sheet Excel，使其从同一已提交 V2 数据投影正式结果、待确认结果与非阻断反馈。
- 历史 V1 任务、已有数据库记录和制品保持只读兼容；新链路不支持时安全失败，禁止静默回退 V1。

## Capabilities

### New Capabilities

- `testcase-generation-v2`: 覆盖 V2 已审核范围输入、KEE 合同适配、业务接收、事务恢复、状态投影、页面和 Excel 同源输出及 V1 历史兼容。

### Modified Capabilities

无。

## Impact

- 后端：`task`、`knowledgeagent`、`scope`、`structuredgeneration`、`validation`、`testcase`、`export` 与 `web` 模块。
- 数据库：新增向前 Flyway 迁移保存 V2 工作流版本、已审核范围、需求事实/引文、可测性反馈、生成结果和幂等发布账本；不改写 V14-V19。
- 前端：任务详情的处理状态、覆盖结果、反馈、正式/待确认用例和旧任务兼容投影。
- 外部接口：只依赖 KEE 冻结的 `2.0` 合同；不得新增未冻结字段或固定模型输出 token 上限。
- 导出：仍恰好两张工作表，按 V2 已提交数据投影，并保留历史 V1 制品读取。
