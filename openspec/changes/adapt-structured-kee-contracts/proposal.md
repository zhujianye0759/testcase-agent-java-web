## Why

KEE 已冻结 `parsed-units` 与结构化 `isolated-skill` 契约，但 Java Web 当前仍按 SSE、Markdown、Skill 准备会话和固定 `2N` 处理模型结果。Java 侧需要先形成可审查的适配规格与设计，在 KEE 代码验收和部署可用之前明确消费者合同、业务校验、持久化与完成门禁，避免把“规格已冻结”误报为“接口已部署”。

## What Changes

- **BREAKING**：Java 对现有 `POST /api/v1/agent-chat/{sessionId}/isolated-skill` 的调用设计改为六字段同步 JSON 合同，不再按 SSE、Markdown、`read_skill` 工具事件或 AgentEngine 终态接收结果。
- Java 通过冻结的 `GET /api/v1/knowledge/{knowledgeId}/parsed-units` 合同完整遍历持久化解析文本；任一页失败、重复、遗漏、游标循环或总数不一致均废弃整次遍历。
- Java 为 `requirement-material-quality-review`、`feature-scope-reconciliation`、`functional-testcase-design` 建立各自的输入/结果 DTO 和严格消费者合同；材料切片保留全局 `ordinal`，首值不小于 1 且切片内严格连续。
- `feature-scope-reconciliation` 使用严格 `operation` 区分功能清单提取与双向核对：`extract_function_list` 只从功能清单切片提取无 `item_key` 的条目，Java 校验证据后生成任务内稳定 `item_key`；`reconcile` 才接收已聚合的功能清单条目和需求事实。不得新增第四个 Skill、复用普通 Agent/Markdown 或由 Java 猜测 Excel 层级。
- KEE 负责结构校验和最多一次格式修复；Java 负责所有 `*_key`/`evidence_key` 引用、证据归属、终态、正式覆盖、状态映射及持久化业务规则，模型结果不得直接入库。
- 取消固定正反两条和 `2N` 门禁；正式测试点至少有一条 `formal` 用例，`general_experience` 只能产生 `pending_confirmation` 用例且不计入正式覆盖。
- 处理状态与覆盖结果分离。页面只显示 Java 已校验并保存的结构化数据，不展示原始 JSON 或 Markdown 预览；Excel 固定为“需求与功能清单审查发现”和“测试用例”两个 Sheet。
- 普通 Agent Chat、RAG、Wiki、PageIndex、图谱、Web/MCP、history/Memory、附件、图片、`read_skill`/Sandbox 和普通 Skill 模式保持不变。
- KEE 基础结构化接口提交为 `ba21fecf`；`extract_function_list`/`reconcile` 增补合同已由 KEE 主线程完成复审、聚焦与普通 Agent 回归并冻结于本地提交 `4c68f2f8`。该提交尚未部署或推送；Java 可完成本地 DTO、编排和业务校验 GREEN，但真实 E2E 继续等待部署通知与精确镜像证据。

## Capabilities

### New Capabilities

- `structured-kee-integration`：定义 Java 对冻结 `parsed-units` 和同步结构化 `isolated-skill` 的消费者合同、隔离边界、大小限制及失败关闭规则。
- `structured-test-generation`：定义三类 Skill 结果的 Java 业务校验、证据引用、终态收敛、非固定数量用例、持久化和完成门禁。
- `structured-generation-delivery`：定义已校验结构化数据的任务详情展示与固定双 Sheet Excel 交付，不暴露 KEE 原始 JSON 或 Markdown。

### Modified Capabilities

<!-- 当前仓库尚无已归档基础 spec。本 change 明确替代活动 change `all-completeness-cross-audit` 中 SSE、Markdown、Skill 准备会话和固定 2N 的规划条款，但不覆盖其现有未提交文件。 -->

## Impact

- Java 后端：`knowledgeagent` 端口与 HTTP 适配器、`scope`、`featureaudit`、`requirementquality`、`testcase`、`validation`、`task`、MySQL/Flyway 和 `export`。
- 前端：任务详情从 Markdown 行投影迁移为已校验结构化业务投影，保留既有任务操作和 PC 状态处理。
- 外部 KEE：只消费 `ba21fecf` 已冻结的路径与字段；Java 不修改 KEE，也不声称其结构化接口已经部署。
- 验证：先用消费者合同测试和纯 Java 业务不变量完成 RED→GREEN；真实集成必须等待 KEE 代码验收、可部署版本和精确运行证据。
