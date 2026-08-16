## Why（变更原因）

当前 `ALL` 将模型生成的一份 Markdown 功能清单视为完整生成范围，因此即使响应仅包含九个功能，仍可创建九个成功批次并进入 `COMPLETED`。系统需要可验证的材料覆盖台账和功能清单/需求双向审查，避免将缺失的源内容、无法说明的映射及未生成的功能呈现为完整交付。

## What Changes（变更内容）

- 在功能生成开始前，通过已开发、待联合部署验收的 KEE V1 `GET /api/v1/knowledge/{knowledge_id}/parsed-units` 契约，枚举当前最新、已授权文档已经持久化的文本 Chunk；不请求或传递历史版本参数。
- 上传和解析必须在调用前完成，且文件在任务中不处于主动替换状态。调用方若明确获知文件已替换，Java 丢弃已接收的部分结果并从第一页重新开始；不得假定或实现自动检测。
- 分页默认 50 个 Chunk，最大 100；请求超过 100 时按 100 处理。每个序列化响应页最大 4 MiB；单个 Chunk 超过该上限时返回 `unit_too_large`，不得截断文本。正常续页使用 `next_cursor`。
- Java 累计 `ordinal` 与 `total_units`，并仅在末页 `complete=true`、计数一致、单元唯一有序且游标无循环或缺口时通过材料遍历门禁。`complete=true` 只证明本次读取可见的持久化文本 Chunk 已枚举，不代表 Word、PDF 或 Excel 的原生解析保真度。
- 持久化源单元、提取出的功能候选项、双向审查结论和最终冻结功能集合的耐久覆盖台账；测试用例证据仅保留 KEE 文档/Chunk 引用。
- 双向审查：仅存在于需求中的功能记为 `功能清单遗漏`；仅存在于功能清单中的功能记为 `需求未覆盖该功能点`；冲突、重复、拆分、合并和证据不足均保留为可追溯的候选发现。
- 每个有界审查单元、最终双向核对和单功能生成批次均先创建独立的 Skill 准备会话：审查显式传入 `feature-scope-reconciliation`，生成显式传入 `functional-testcase-design`；准备和业务请求仅走 KEE `isolated-skill` 专用入口，且每次调用均以同一 ID 的 `read_skill` 声明、精确参数、成功结果及完成终态为接收门禁。旧 agent-chat 404 不回退；非 `read_skill`/`execute_skill_script` 工具直接失败关闭。任一准备失败均拒绝整个当前审查或生成阶段，不继续领取后续单元或批次。
- 仅在每个源单元和候选项均已有终态结论后冻结最终功能集合；重试复用该冻结集合。
- 对每个可生成的冻结功能生成一个隔离批次，且恰好接收一个正向和一个反向测试用例。
- 仅在源覆盖、审查收敛、冻结范围、所有批次结果及 `2N` 测试用例不变量均通过时，才允许进入 `COMPLETED`。
- 保留现有固定 Markdown 生成响应的两张表和 Excel 的两个 Sheet；最终详情、Markdown 和工作簿确定性清理 `documentId`、`unitId`、`candidateIds` 机器绑定，不得恢复已废弃的模型 JSON 协议，也不得新增技术工作表。
- 展示面向业务的材料、审查、冻结功能和测试用例进度，但不暴露文档 ID、源游标或内部功能 ID。
- 将增量、通用的 KEE 解析材料单元读取 API 作为外部前置条件。只读验证已证明 `/preview` 文本稳定，但无法证明当前读取中已持久化解析文本 Chunk 的完整枚举；原生解析保真度另以代表性 PDF、DOCX 和 XLSX 样本验收。

## Capabilities（能力）

### New Capabilities（新增能力）

- `structured-material-read`：通过固定的 KEE parsed-units HTTP 合同将当前最新的已授权 KEE 文档读取为可分页的持久化文本 Chunk；对范围失败、遍历缺口、重复、乱序、计数不一致、游标错误、超大单元或截断实行失败关闭。
- `kee-skill-invocation`：按业务阶段请求两个精确的测试专业 Skill，并以 SSE 工具事件验证 `read_skill` 成功；不引入 Skill 版本、哈希、manifest 或新 Skill API 依赖。
- `bidirectional-feature-audit`：在功能清单条目和需求功能之间构建完整的交叉审查台账，包括遗漏、冲突、拆分、合并、重复和证据不足。
- `complete-all-generation`：冻结可审计的最终功能集合，为每个可生成的功能恰好生成一个正向和一个反向测试用例，并施加如实的任务/导出完成门禁。
- `completeness-web-reporting`：在隐藏技术范围标识符的同时，展示面向业务的覆盖情况、审查情况、生成进度及结果不完整的原因。

### Modified Capabilities（修改能力）

<!-- 尚不存在已归档的基础 spec。新增能力替代当前 MVP change 中不完整的 ALL 语义，但不修改该已完成 change。 -->

## Impact（影响）

- Java Web：KEE 适配器端口、任务流程/状态校验、MySQL/Flyway 持久化、Markdown 发现/审查解析、批次校验、Excel 汇总、任务 API 及 Vue 任务详情进度。
- KEE：`add-parsed-document-structure-api` 与 `add-testing-professional-skills` 已在独立分支完成开发、review、定向验证、真实运行验收和回滚演练，但尚未合并、推送或归档，当前运行环境已回滚旧镜像。Java 在本 change 中以 WireMock 固化消费者合同；真实联合验收时临时部署这两个 KEE change。它们不得修改 RAG 检索、排序、重排序、PageIndex、Wiki、普通 Agent/SSE 或最终回答行为。
- 证据：既有 KEE 文档/Chunk 证据始终是唯一正式证据模型；Java 覆盖台账只引用该证据，不创建第二套来源证据模型。
- 数据：新增应用自有的审查和冻结功能记录；KEE 数据库保持外部边界，绝不复用。
- 兼容性：指定功能生成以及现有两张 Markdown 表/两个 Excel Sheet 保持兼容。不会改写既有已完成制品。
