## 背景

Java 已有结构化工作登记、认领、租约、尝试记录、已接受结果哈希、正式功能清单、需求评审、功能核对、测试点、测试用例、任务详情和双工作表导出能力。本变更不创建第二套工作流，而是在现有边界内增加“规范候选与原文处理台账”。

当前 Java 工作树包含尚未提交的长超时、租约心跳、显式重试、分页核对和正式测试用例约束。实施必须复用这些现有能力并保留所有既有修改；不能回滚、覆盖或通过大范围重构掩盖冲突。

## 目标与非目标

### 目标

- 精确调用和校验 KEE 协议 V1，并保留旧提取接口兼容性。
- 让每个目标原文单元都有可审计结论。
- 分离 KEE 建议、Java 决定、正式功能和任务覆盖。
- 逐窗口原子保存候选台账和正式投影，保持幂等、租约及重启安全。
- 容量或格式失败只影响当前窗口，已完成同级保持不变。
- API、页面和 Excel 使用同一批已提交记录，并真实展示部分完成。

### 非目标

- 不修改或放松旧 `extract_function_list`。
- 不让 Java 解析 KEE 内部模型中间格式，也不让前端决定业务状态。
- 不重新上传、解析、OCR 或 PageIndex 建树。
- 不新增候选编辑器、第三个 Excel 工作表或原始诊断接口。
- 不改变普通任务、Markdown 路径或 KEE 普通功能。

## 设计决策

### 1. 复用现有远程调用边界

`StructuredSkillExecutionPort` 增加精确的候选调用；`WebClientKnowledgeAgentAdapter` 负责固定 JSON 映射和稳定错误类型。Java 只接收 KEE 的公开规范对象，不读取操作内部模型 DTO。新协议不受支持或字段不合法时安全失败，绝不退回旧操作。

### 2. 纯校验器拥有 Java 最终决定规则

新增 `FunctionCandidateExtractionValidator`。输入为冻结窗口和 KEE 规范响应，输出为经过 Java 校验的原文决定与候选决定，或一个安全接口失败。该组件重新计算 `window_key`、`candidate_ref`，校验目标闭包、原文顺序、候选引用、证据归属、引用连续性和状态/原因组合，并执行“只能保留或降级”的规则。它不执行 HTTP、SQL、重试、页面或 Excel 操作。

### 3. 确定性身份复用现有长度前缀哈希

`window_key` 直接使用 `structured_generation_work_item.identity_key`。Java 使用现有 `LengthPrefixedSha256`，按冻结的域分隔符、任务、材料、目标数量/键值和上下文数量/键值计算。`candidate_ref` 使用同样的四字节大端长度前缀规则，根据窗口、规范化路径、描述、目标引用和有序证据键计算。具体字面量在两个仓库共用测试样例中冻结，不能由测试重新计算预期值。

### 4. 只新增三张规范化审计表

- `structured_function_source_outcome`：每个工作窗口、每个目标单元一行，保存 KEE 建议处理结论、Java 最终结论和原因。
- `structured_function_candidate`：每个规范候选一行，保存展示字段、KEE 建议状态、Java 最终状态、原因、缺失信息和可为空的正式功能键。
- `structured_function_outcome_candidate`：保存原文处理结果和候选的多对多关系，同时作为候选目标证据关系。

继续复用 `structured_generation_work_item.accepted_result_sha256`；不增加第二张窗口表，不扩展现有正式 `structured_reference_binding` 的候选前置语义。

### 5. 保存与正式投影共用一个接受事务

`StructuredGenerationAcceptanceStore` 在认领、租约、尝试和冻结输入仍匹配时，一次性写入三张台账表，并为 Java 最终接受候选调用现有正式功能所有权/去重逻辑。事务失败时全部回滚。相同工作身份和相同规范哈希重放时不重复写入；同一身份对应不同规范内容时安全失败。

待确认、拒绝、无法确定和不包含功能的记录只保留在候选审计范围，不进入正式功能、核对、测试点或测试用例表。

### 6. 在同一任务内拆分和恢复

每次候选窗口的目标与上下文合计最多 32 个。`request_too_large`、`response_too_large` 或多目标 `structured_output_invalid` 在现有工作树中只拆分当前目标范围；子窗口根据同一冻结材料重新计算上下文。`model_execution_failed` 沿用有界重试和现有租约心跳。单目标最终失败保留为失败叶子。已完成同级不重新调用 KEE，不重新解析材料，也不创建替代任务。

### 7. 任务完成状态由交付事实决定

`StructuredCompletionGate` 继续分别计算处理状态和覆盖状态。`GenerationTaskRepository.completeStructuredTask` 不得无条件写 `COMPLETED`：

- 全部必需范围与正式覆盖完成：`COMPLETED`；
- 已有正式可用成果，但仍有必需窗口、原文或候选未关闭：`PARTIAL`；
- 没有可信可用成果或关键完整性失败：`FAILED`。

部分完成的成果文件可以发布，但必须在 API、页面和 Excel 中明确标识缺口，不能把缺口计入正式覆盖。

### 8. 页面和 Excel 只读取已保存投影

`GenerationTaskRepository.structuredResult(...)` 和 `structuredWorkbookRequest(...)` 从同一批已提交台账/正式记录构造用户结果。`StructuredGenerationTaskDetail` 只暴露中文业务说明和数量，不暴露内部键、原始 JSON、模型输出、堆栈或凭据。

`ApachePoiWorkbookExporter` 继续严格生成两个工作表：

1. `需求与功能清单审查发现`：合并需求问题、候选/原文缺口、坏例、好例和测试设计影响；
2. `测试用例`：只包含 Java 最终接受范围生成的正式测试用例。

### 9. 部署和回滚

先部署已支持协议 V1 的 KEE，再部署 Java。Java 切换前运行跨语言固定样例；Java 部署后先做合成 API/数据库/页面/Excel 验收，最后才允许一个真实梅州任务。回滚先恢复 Java 到旧调用路径；新增台账表保留但不再使用，历史正式业务记录兼容。不得重新解析文档。

## 影响范围

主要修改符号：`StructuredSkillExecutionPort`、`WebClientKnowledgeAgentAdapter`、新候选 DTO/校验器、`StructuredMaterialSlicePlanner`、`StructuredGenerationAcceptanceStore`、`DefaultStructuredAllGenerationCoordinator`、`StructuredCompletionGate`、`GenerationTaskRepository`、`StructuredGenerationTaskDetail`、结构化导出行、`taskApi.ts` 和 `TaskDetailView.vue`。

主要验证边界：远程 JSON 映射、纯候选校验、真实 MySQL 迁移/事务/幂等/租约、编排拆分和恢复、完成状态、已保存 API 投影、双工作表导出和前端中文显示。

## 风险与控制

- 既有 Java 未提交内容被覆盖：实施前后盘点差异，只做任务范围内增量修改。
- KEE/Java 接口漂移：共用固定 JSON 样例和独立字面量哈希向量。
- 一个坏候选污染正式结果：Java 独立校验并只投影最终接受候选。
- 事务部分写入：真实 MySQL 回滚、租约和并发单赢家测试。
- PARTIAL 被误报为完成：API、数据库、页面和 Excel 四方对账。
- 新协议影响旧流程：旧提取、普通任务、Markdown 和 KEE 普通功能定向回归。

## 待确认问题

没有产品或架构未决问题。具体列类型和索引名称由迁移测试按当前 MySQL/Flyway 约定确定，但不得改变三张表的职责和公开协议。
