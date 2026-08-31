## ADDED Requirements

> 中文阅读说明：`Requirement`、`Scenario`、`WHEN`、`THEN`、`AND`、`SHALL`、`MUST`、`MAY` 是 OpenSpec 固定语法。`SHALL`/`MUST` 表示“必须”，`MAY` 表示“允许”。

### Requirement: [REQ-AFCE-001] Java 必须构造并发送精确的协议 V1 候选窗口

Java Web SHALL（必须）通过 `StructuredSkillExecutionPort` 调用 `skill_name="feature-scope-reconciliation"` 和 `operation="extract_function_candidates"`，并发送精确的 `protocol_version="1"`、`window_key`、`material_key`、`source_label`、`units` 和 `context_units`。`window_key` SHALL 使用当前任务冻结工作项的确定性 64 位小写十六进制身份。

目标单元 SHALL 连续、按原顺序排列且至少一个；目标与上下文合计 SHALL 不超过 32 个。上下文 SHALL 来自同一冻结材料、按序且与目标不重叠。Java MUST NOT 发送整份文档、未冻结单元或另一个材料/任务的单元。

#### Scenario: Java 发送一个有效候选窗口
- **WHEN** Java 根据冻结工作项构造目标和上下文窗口
- **THEN** 远程请求 SHALL 与共用协议 V1 固定样例一致
- **AND** `window_key` SHALL 等于该工作项的确定性身份

#### Scenario: Java 无法构造可信窗口
- **WHEN** 目标为空、不连续、与上下文重叠、合计超过 32 个或冻结坐标不完整
- **THEN** Java SHALL 在调用 KEE 前安全失败

### Requirement: [REQ-AFCE-002] Java 必须严格校验每个目标原文单元的处理闭包

Java SHALL 严格解码 KEE 公开规范结果，并要求 `source_outcomes` 按目标输入顺序为每个目标单元恰好提供一条 `linked`、`no_function` 或 `unresolved` 结果。上下文 MUST NOT 拥有处理结果或证据。每个候选引用 SHALL 指向同一结果中的候选，每个候选 SHALL 能从至少一个目标原文结果到达。

`unresolved` SHALL 只允许空 `candidate_refs`，且 `reason_code` SHALL 为 `ambiguous_content`、`conflicting_content`、`model_omitted_unit` 或 `model_item_unusable`。`pending_confirmation` 候选 SHALL 提供非空 `missing_information`，且 `reason_code` SHALL 为 `ambiguous_scope`、`insufficient_detail` 或 `conflicting_evidence`。

`normalization_summary.model_candidate_count` 表示去重前的模型原始候选数。Java SHALL 要求公开候选数与丢弃候选数之和不大于模型原始候选数，丢弃候选数不大于模型原始候选数，降级候选数不大于模型原始候选数减去丢弃候选数。`auto_unresolved_unit_count` 只表示规范化器自动补充的遗漏单元，SHALL 不大于全部 `unresolved` 数量，也 SHALL 不大于 `reason_code=model_omitted_unit` 的 `unresolved` 数量。

任何未知字段、缺失目标结果、顺序错误、非法状态/原因组合、上下文证据、悬空引用或不一致规范化数量 SHALL 使整个公开结果被拒绝，并且该窗口保持零写入。

#### Scenario: KEE 漏掉一个目标结果
- **WHEN** 公开规范结果缺少任一目标单元的 `source_outcome`
- **THEN** Java SHALL 把它判定为接口无效并保持零业务写入

### Requirement: [REQ-AFCE-003] Java 必须独立验证候选依据并拥有最终决定权

Java SHALL 校验每个候选的路径、描述、目标连续引用、目标证据键、建议状态、原因码和缺失信息。Java MAY 保留或降低 KEE 的建议等级，但没有新的已授权证据时 MUST NOT 升级。Java 最终决定 SHALL 只能是 `accepted`、`pending_confirmation` 或 `rejected`。

只有 Java 最终 `accepted` 的候选 SHALL 创建或关联正式功能清单，并进入正式功能核对、测试点、测试用例和覆盖计数。其他候选和无法确定/无功能原文结果 SHALL 保持可审计，但不得进入正式表。

#### Scenario: Java 降低一个 KEE 建议
- **WHEN** KEE 建议接受候选，但 Java 的确定性证据校验只能支持待确认
- **THEN** Java SHALL 分别保存 KEE 建议和 Java 最终决定
- **AND** 该候选 SHALL NOT 进入正式功能清单

### Requirement: [REQ-AFCE-004] Java 必须独立重新计算跨语言身份

Java SHALL 使用冻结的长度前缀 SHA-256 规则重新计算 `window_key` 和每个 `candidate_ref`。预期摘要 SHALL 来自两个仓库共用的已审查字面量测试向量，不得在测试中用同一实现重新生成预期值。

相同身份对应不同规范内容、错误哈希、证据顺序漂移或文字规范规则不一致时 SHALL 安全失败。

#### Scenario: KEE 返回错误候选身份
- **WHEN** 候选内容和证据根据协议计算出的摘要与 `candidate_ref` 不同
- **THEN** Java SHALL 拒绝完整窗口并保持零写入

### Requirement: [REQ-AFCE-005] Java 必须逐窗口原子保存审计台账和正式投影

Java SHALL 在现有结构化工作认领和租约保护下，通过同一事务写入 `structured_function_source_outcome`、`structured_function_candidate`、`structured_function_outcome_candidate`、已接受结果哈希以及 Java 最终接受候选对应的正式功能清单记录。

事务失败 SHALL 不留下任何候选台账或正式业务行。相同工作身份和相同规范哈希重放 SHALL 幂等；相同身份对应不同规范哈希 SHALL 安全失败。已完成同级窗口和既有正式记录 MUST NOT 被覆盖。

#### Scenario: 候选保存事务失败
- **WHEN** 写入台账、关系或正式功能中的任一步失败
- **THEN** 整个窗口事务 SHALL 回滚
- **AND** 工作项 SHALL NOT 被标记为已接受

### Requirement: [REQ-AFCE-006] Java 顶层任务、API、页面和 Excel 必须真实一致

Java SHALL 分别保存结构化处理状态和覆盖状态，并根据真实交付结果投影顶层状态：全部必需范围完整时为 `COMPLETED`；已有正式可用成果但必需范围不完整时为 `PARTIAL`；没有可信成果或关键完整性失败时为 `FAILED`。编排器停止运行 MUST NOT 自动等同于完成。

API、页面和 Excel SHALL 从同一批已提交记录生成中文结果。Excel SHALL 恰好包含 `需求与功能清单审查发现` 和 `测试用例` 两个工作表；候选/原文缺口只进入第一张表，第二张表只包含正式测试用例。用户输出 MUST NOT 暴露原始 JSON、模型输出、内部键、英文枚举、堆栈或凭据。

#### Scenario: 有正式成果但一个必需窗口最终失败
- **WHEN** 已完成窗口产生正式功能和测试用例，但一个必需单目标窗口最终技术失败
- **THEN** 任务 SHALL 为 `PARTIAL`
- **AND** API、页面和 Excel SHALL 标明未完成范围且不得计入正式覆盖

### Requirement: [REQ-AFCE-007] Java 只能重试或拆分受影响窗口

Java SHALL 保证每次请求目标与上下文合计最多 32 个，不设置模型最大输出 Token，并保留已批准的长调用超时和租约心跳。发生 `request_too_large`、`response_too_large` 或多目标 `structured_output_invalid` 时，Java SHALL 只按确定性中点拆分当前目标范围并重新计算上下文。`model_execution_failed` MAY 按现有有界策略重试同一窗口。

单目标窗口最终失败 SHALL 保留为失败叶子。已完成同级 MUST NOT 被重新调用、重新解析、覆盖或与另一个任务合并。Java MUST NOT 拼接截断输出、创建替代任务或重新解析文档。

#### Scenario: 多目标窗口响应过大
- **WHEN** 一个多目标候选窗口返回 `response_too_large`
- **THEN** Java SHALL 在同一任务和冻结材料内建立两个非空确定性子窗口
- **AND** 已完成同级窗口 SHALL 保持不变

### Requirement: [REQ-AFCE-008] 新协议不得破坏旧流程或扩大材料范围

Java SHALL 只把正式功能提取切换到协议 V1；旧 `extract_function_list`、其他结构化操作、普通任务和 Markdown 路径 SHALL 保持现有行为。新协议不受支持时 Java SHALL 返回安全部署不匹配，不得静默退回旧操作。

本变更 MUST NOT 触发文档重新上传、解析、OCR、PageIndex、DocReader、RAG、Wiki、知识图谱或全局模型配置变更，也不得修改其他知识库或任务数据。

#### Scenario: Java 连接到不支持协议 V1 的 KEE
- **WHEN** KEE 返回操作或协议不受支持
- **THEN** Java SHALL 安全失败并保持候选/正式表零新增
- **AND** SHALL NOT 调用旧提取操作重试
