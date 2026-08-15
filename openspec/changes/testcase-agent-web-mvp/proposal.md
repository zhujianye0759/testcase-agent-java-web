## Why

内部测试人员目前缺少一个可共享、可恢复且易操作的 Web 工具，将 KnowledgeEngineeringEngine 已有测试用例智能体输出的 Markdown 审查发现和测试用例稳定汇总为 Excel。第一期优先证明“现有智能体 + Java 任务编排 + 两张业务表”能够完成真实材料的生成闭环，不把模型输出改造成一套复杂业务 JSON 协议。

## What Changes

- 在独立仓库中建设 Java 17 + Spring Boot 3 + MySQL 8 的模块化单体，提供持久任务、最多 5 个并发执行、排队、取消、失败批次重试和重启恢复。
- 通过受限服务端凭据调用 KnowledgeEngineeringEngine 的 Agent 发现、会话、SSE 智能体和知识检索接口；浏览器不接触内部凭据或资源 ID。
- 固化不可变需求范围与示例范围快照，严格区分正式需求证据和 Few-shot Good/Bad 示例，越界、截断或缺失终态时失败关闭。
- 智能体按固定 Markdown 返回两张表：`需求与功能清单审查发现` 和 `测试用例`；Java 仅解析固定表头、校验行结构并按批次持久累加，不要求模型生成复杂 JSON。
- 使用 Apache POI 确定性生成并回读验证 `.xlsx`，仅包含 `需求与功能清单审查发现` 和 `测试用例` 两个 Sheet；失败批次重试不得重复已接受数据。
- 提供 Vue 3 PC Web 业务化流程：默认“生成全部测试用例”，也可“指定功能生成”，已授权材料范围自动选择或用可读标签选择；用户不接触 UUID、JSON、功能点 ID、Few-shot 技术枚举或项目坐标。
- 先以一个功能点完成 RED→GREEN 切片，再支持 `ALL` 模式；最终只对战略运管知识库 `abd55572-af4b-44e9-820b-3a8a5e645664` 的 V1.0 准入材料执行一次全量真实验收。
- 第一阶段不设置功能点数量、任务总时长或 Excel 保留天数的产品硬上限，但所有内存处理、单批调用、超时和重试必须有界。

## Capabilities

### New Capabilities

- `generation-task-lifecycle`: 创建、排队、查询、取消、失败批次重试、最多 5 个并发任务以及重启恢复。
- `knowledge-agent-integration`: 受限凭据下的智能体发现、会话创建、Markdown SSE 终态和错误映射。
- `scoped-generation-contract`: 需求/示例范围快照、文档白名单、Few-shot 隔离、Markdown 行校验和失败关闭。
- `testcase-analysis`: 审查发现和测试用例 Markdown 行的解析、跨批累加及生成完整性。
- `testcase-workbook-export`: 两 Sheet 安全 Excel 生成、公式注入防护、哈希与回读验证。
- `generation-web-workflow`: 符合 PC UI 规范的创建、列表、详情、进度、共享操作和下载页面。

### Modified Capabilities

无。当前仓库为全新项目，没有既有能力需要修改。

## Impact

- 新增 Java 后端、Vue 前端、MySQL 8/Flyway 数据模型、受控本地产物目录及开发/测试配置。
- 外部依赖为 KnowledgeEngineeringEngine 正式 HTTP/SSE API、其测试用例智能体和部门级示例知识库。本 change 将精确恢复此前仅为测试用例 JSON 特殊协议加入的 KEE 公共逻辑，并以定向回归证明自然语言问答、范围隔离和检索不退化；不得覆盖或回退 KEE 工作区的其他既有改动。
- 第一阶段所有内网使用者共享查看、取消、重试和下载操作；登录、角色、权限、审批、模板管理、图片写入 Excel、自动沉淀回知识库和专用 AI 智能体均为非目标。
- 真实模型质量结论只来自一次受控基线和一次必要的最终验收；契约测试和故障测试使用确定性模拟，不以反复全量调用模型碰运气。
