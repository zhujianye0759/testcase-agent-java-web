## Why

当前 Java Web 把 KEE 的 `extract_function_list` 结果作为整批对象校验：一条候选的引用或证据归属错误，会使同一窗口中其他正确候选一起失败；而整批成功也不能证明每个目标原文单元都已被检查。这种做法会把不同项目材料的正常差异放大成反复提示词修复，不能稳定支撑后续系统。

Java 必须从“接收模型生成的正式功能”改为“接收 KEE 规范候选、独立决定并保存审计台账”。只有 Java 最终接受的候选才能进入正式功能清单和测试用例生成；无法确定或材料缺失必须真实反映在任务状态、页面和 Excel 中。

## What Changes

- 新增对 KEE `extract_function_candidates` / `protocol_version="1"` 的精确 Java DTO、远程调用和独立校验，不修改旧 `extract_function_list` 接口。
- 复用现有结构化工作项 `identity_key` 作为 `window_key`，并按冻结材料窗口生成确定性跨语言身份。
- 对每个目标原文单元校验且只接受一条 `linked`、`no_function` 或 `unresolved` 结果；上下文不得拥有结果或证据。
- 分别保存 KEE 建议和 Java 最终决定，只允许 Java 最终 `accepted` 的候选进入正式功能清单和下游正式生成。
- 在现有接受事务中新增三张规范化候选审计表，保持逐窗口原子写入、幂等、租约和零部分写入。
- 按当前窗口执行有界重试或确定性拆分，不重新解析材料、不创建替代任务、不重放已完成同级窗口。
- 修正顶层任务状态：完整交付为 `COMPLETED`，存在可用成果但必需范围不完整为 `PARTIAL`，无可信成果为 `FAILED`。
- 从同一批持久化记录生成中文任务详情和恰好两个 Excel 工作表；正式测试用例只来自 Java 接受的正式功能与需求证据。

## Capabilities

### New Capabilities

- `auditable-function-candidate-extraction`：Java 对 KEE 候选协议进行独立校验、最终决定、逐窗口原子保存、恢复编排、真实状态投影和双工作表交付。

### Modified Capabilities

None.

## Impact

- Java knowledge-agent DTO、`StructuredSkillExecutionPort`、`WebClientKnowledgeAgentAdapter` 和严格结果映射。
- `StructuredMaterialSlicePlanner`、`DefaultStructuredAllGenerationCoordinator`、`StructuredCompletionGate` 和任务完成仓储方法。
- `StructuredGenerationAcceptanceStore` 与一份新增 Flyway 迁移；保留现有工作、尝试、租约、哈希、正式业务行和引用关系。
- `StructuredGenerationTaskDetail`、任务详情 API、前端任务页和 `ApachePoiWorkbookExporter` 的已保存数据投影。
- 不修改 KEE 普通 RAG/Agent、文档解析、OpenDataLoader/OCR、PageIndex、DocReader、Wiki、知识图谱或全局模型配置；不重新上传或解析梅州材料。
