## ADDED Requirements

### Requirement: [REQ-SMR-001] 按固定 parsed-units 合同读取当前最新授权材料
系统 SHALL 通过 `GET /api/v1/knowledge/{knowledge_id}/parsed-units` 读取每一份当前最新、已授权的功能清单和需求文档。上传与解析 SHALL 在调用前完成，文件 SHALL NOT 在任务运行中主动替换。每一页 SHALL 复用 API-key、tenant、KB、系统、版本、材料类型、可选项目和文档白名单校验；越界 SHALL 按既有 `forbidden` 失败关闭。

响应 `data` SHALL 只含 `knowledge_id`、`total_units`、`units`、`next_cursor`、`complete`。每个 `unit` SHALL 只含 `unit_id`、`chunk_index`、`ordinal`、`content`、`start_at`、`end_at`。契约 SHALL 只读取该文档当前可见的已持久化文本 Chunk，不得接受或返回历史版本、revision、hash 或 snapshot 身份。

未指定 `limit` 时 SHALL 返回至多 50 个单元；`limit` 大于 100 时 SHALL 按 100 处理。正常续页 SHALL 使用不透明 HMAC keyset `next_cursor`。每个完整 JSON 响应页 SHALL NOT 超过 4 MiB；单个单元无法装入该上限时 SHALL 返回 `unit_too_large`，且 SHALL NOT 截断该单元文本。显式业务错误 SHALL 包括 `document_not_ready`、`cursor_signing_unavailable`、`invalid_cursor`、`document_not_current`、`parsed_unit_integrity_error` 和 `unit_too_large`。

#### Scenario: 默认、最大和超限分页
- **WHEN** 调用方未指定分页大小、指定 100，或指定大于 100 的大小
- **THEN** 服务分别按 50、100、100 个单元处理请求
- **THEN** Java 继续使用返回的 `next_cursor` 遍历后续页

#### Scenario: 显式业务错误
- **WHEN** KEE 返回任一 parsed-units 显式业务错误或既有 `forbidden`
- **THEN** Java 拒绝该文档的整次遍历
- **THEN** 系统不得将已接收的部分单元表示为完整材料

### Requirement: [REQ-SMR-002] 以连续单元和显式末页完成材料遍历门禁
系统 SHALL 从无游标的第一页开始遍历每份文档。Java SHALL 验证每页 `knowledge_id` 等于请求文档，累计单元数恰好等于 `total_units`，`unit_id` 全局唯一，`ordinal` 严格连续为 `1..total_units`，且 `next_cursor` 无循环或缺口。仅当最终页同时返回 `next_cursor=null` 和 `complete=true` 时，Java 才能将文档标记为已遍历解析材料；任一页 HTTP、JSON、范围、游标或单元完整性失败 SHALL 使整次遍历失败。

#### Scenario: 完整分页遍历
- **WHEN** 一份当前最新、已授权文档包含多于一个响应页的单元
- **THEN** Java 以 `unit_id` 恰好一次接收单元，并验证 `ordinal` 严格连续
- **THEN** Java 仅在收齐 `total_units` 且最终页 `next_cursor=null`、`complete=true` 后通过材料门禁

#### Scenario: 游标或末页边界不符合合同
- **WHEN** 游标重复、缺失、提前为空，或 `complete=true` 出现在非最终页，或最终页未同时满足 `next_cursor=null` 与 `complete=true`
- **THEN** Java 拒绝整次遍历
- **THEN** 任务不得进入依赖该材料的审查或完成阶段

#### Scenario: 调用方明确获知文件已替换
- **WHEN** 调用方在枚举完成前明确报告该文件已替换
- **THEN** Java 丢弃已接收的部分结果并从第一页重新读取
- **THEN** 系统不得自动比较内容或推断文件是否替换

### Requirement: [REQ-SMR-003] 保留 KEE 文档/Chunk 证据引用而非原文件坐标
每个已接收解析单元 SHALL 保留既有 KEE 文档/Chunk 证据引用及其 `ordinal`。既有 KEE 文档/Chunk 证据 SHALL 是唯一正式来源证据；覆盖台账 SHALL NOT 建设第二套来源证据模型。`complete=true` 仅证明本次读取可见的持久化文本 Chunk 已枚举；契约 SHALL NOT 提供或保证 PDF/Word 页、Excel Sheet/行/单元格、原生结构或解析保真度。原生解析保真度须另以代表性 PDF、DOCX 和 XLSX 样本验收。

#### Scenario: 解析文本中展示的业务序号重复
- **WHEN** 两个功能候选出现展示相同业务序号
- **THEN** 系统保留两个候选出现及各自的文档/Chunk 证据引用
- **THEN** 系统不得通过基于展示序号的去重静默丢弃任一候选出现

### Requirement: [REQ-SMR-004] 在回滚运行环境中以确定性消费者测试固化合同
当前 KEE 运行镜像 SHALL 保持旧版本；Java SHALL 不修改 KEE。Java SHALL 使用 WireMock 或等价确定性 HTTP fixture 验证本规范固定 URI、DTO、分页、错误和遍历门禁。真实 HTTP 联合验收 SHALL 等待临时部署已单独验证的 KEE changes；普通 KEE 成功响应不得替代 Java 的材料完成门禁。

#### Scenario: 联合验收前的本地回归
- **WHEN** 当前运行环境尚未临时部署 KEE parsed-units change
- **THEN** Java 通过确定性 fixture 验证消费者行为
- **THEN** 测试不得以旧镜像上路由不存在作为放宽材料门禁的理由
