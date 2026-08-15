## 1. 固定外部合同的消费者测试

- [x] 1.1 针对 KEE 元数据和重复 preview 读取执行 V2.0 只读可行性验证；记录 preview 虽稳定，但无法证明当前读取中已持久化文本 Chunk 的完整枚举。 `[Req-ID]: REQ-SMR-001, REQ-SMR-003`

- [x] 1.2 新建 `backend/src/test/java/com/testcaseagent/knowledgeagent/ParsedUnitsWireMockTest.java`，以 `WebClientKnowledgeAgentAdapter` 的新 parsed-units 调用入口为直接调用方，固化 `GET /api/v1/knowledge/{knowledge_id}/parsed-units`、精确 data/unit 字段、`limit` 默认/100/超限钳制、HMAC cursor 续页、4 MiB 页和六类显式业务错误的 RED fixture。前置：1.1；不修改 KEE。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001, REQ-SMR-004`

- [x] 1.3 在 `ParsedUnitsWireMockTest` 为完整性门禁补齐 RED fixture：从第一页开始、`knowledge_id` 一致、全局 `unit_id` 唯一、`ordinal=1..total_units` 连续、无游标循环/缺口、仅最终 `next_cursor=null` 且 `complete=true`、`unit_too_large` 不截断及调用方明确报告替换后的首页重启。直接调用方为计划新增的 `RequirementMaterialReaderPort.readAll`；前置：1.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001~004`

- [x] 1.4 扩展 `backend/src/test/java/com/testcaseagent/knowledgeagent/WebClientKnowledgeAgentAdapterTest.java`，为审查与生成的 `agent-chat` 请求写 SSE RED fixture：精确 `skill_names`、对应 `read_skill` 的 `tool_call`/`tool_result` 成功、缺失、名称不符、失败、终态错误、截断和明确完成。直接调用方为 `WebClientKnowledgeAgentAdapter.invoke` 及计划新增的审查调用入口；前置：1.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest test`。 `[Req-ID]: REQ-KSI-001~003`

- [x] 1.5 扩展 `backend/src/test/java/com/testcaseagent/markdown/MarkdownParsersTest.java`，以 `MarkdownGenerationResultParser.parse` 为直接调用方，先写拒绝 JSON、代码围栏、改名 H2、改名/增删列、少表/多业务表和非 `<br>` 多项格式的 RED 用例。前置：无。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=MarkdownParsersTest test`。 `[Req-ID]: REQ-CAG-006`

## 2. 材料遍历与耐久台账纵向切片

- [x] 2.1 新建 `backend/src/main/java/com/testcaseagent/scope/RequirementMaterialReaderPort.java`、parsed-units DTO 和 `WebClientKnowledgeAgentAdapter` 实现；完成 1.2、1.3 的 GREEN，使其只消费固定 DTO、当前最新文档和既有范围授权。直接调用方为 `GenerationWorkflow.freezeAllFeatures` 的审查前置步骤；前置：1.2、1.3。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001~004`

- [x] 2.2 新建 Flyway 迁移 `backend/src/main/resources/db/migration/V9__create_material_inventory_and_audit_work.sql`，并扩展 `GenerationTaskRepository` 的材料单元、审查工作和尝试持久化方法；用 `backend/src/test/java/com/testcaseagent/task/MaterialInventoryPersistenceIntegrationTest.java` 证明幂等接收与租约到期后仅重领未完成工作。直接调用方为计划新增的材料审查编排服务；前置：2.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-BFA-001`

- [x] 2.3 在计划新增的 `backend/src/main/java/com/testcaseagent/featureaudit/RequirementMaterialTraversalService.java` 实现读取、逐页校验、原子写入和调用方明确替换后的清空重启；不得把 preview、RAG 回答或部分单元写成完整。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：2.1、2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest,MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001`

## 3. 审查、冻结和 Skill 门禁纵向切片

- [x] 3.1 新建 `backend/src/main/java/com/testcaseagent/featureaudit/FeatureCandidateScanner.java` 及 `FeatureCandidateScannerTest`，从耐久材料单元有界提取功能清单候选项，保留重复展示序号/重复出现而不伪造 Excel 原生行。直接调用方为计划新增的 `FeatureAuditService`；前置：2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureCandidateScannerTest test`。 `[Req-ID]: REQ-BFA-001, REQ-BFA-004`

- [x] 3.2 在 `backend/src/main/java/com/testcaseagent/featureaudit/RequirementCandidateScanner.java` 及 `RequirementCandidateScannerTest` 实现两遍有界需求扫描、第二遍新增项和未收敛失败，不允许未收敛单元进入终态。直接调用方为 `FeatureAuditService`；前置：2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=RequirementCandidateScannerTest test`。 `[Req-ID]: REQ-BFA-002`

- [x] 3.3 新建 `FeatureAuditService`、审查结论 repository 方法及 `FeatureAuditServiceTest`，在调用 KEE 审查前发送且仅发送 `feature-scope-reconciliation`，仅在 SSE 观察到同名 `read_skill` 成功时接收双向结论。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：1.4、2.2、3.1、3.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,FeatureAuditServiceTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-BFA-003, REQ-BFA-004`

- [x] 3.3a 联合验收发现每个材料单元均依赖模型自行 `read_skill` 会造成全量审查失败后，改为审查阶段的隔离准备会话：准备流必须有精确 `feature-scope-reconciliation` 的成功 `read_skill` SSE 证据，随后同一冻结范围的有界审查请求复用该会话；准备失败不得发送业务请求。更新 WireMock/FeatureAuditService RED→GREEN 回归。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,FeatureAuditServiceTest test`。 `[Req-ID]: REQ-KSI-001~003`

- [x] 3.4 在 `FeatureAuditService` 与 `GenerationTaskRepository` 实现每个候选项恰好一个可追溯结论：匹配、`功能清单遗漏`、`需求未覆盖该功能点`、冲突、拆分、合并、重复或证据不足；正式事实只用 document/chunk 证据。直接调用方为冻结服务；前置：3.3。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureAuditServiceTest test`。 `[Req-ID]: REQ-BFA-003, REQ-BFA-004`

- [x] 3.5 新建 `backend/src/main/java/com/testcaseagent/featureaudit/FrozenFeatureService.java` 与 `FrozenFeatureServiceTest`，仅在所有单元/候选/关系已终态时原子冻结稳定 ID、顺序、来源和可生成性，且重试复用冻结结果。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：3.4。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FrozenFeatureServiceTest,MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-BFA-005`

## 4. 单功能生成、两表接收与任务完成纵向切片

- [x] 4.1 重构 `GenerationWorkflow.freezeAllFeatures` 和 `GenerationTaskRepository.planDiscoveredBatches`，以 `FrozenFeatureService` 的结果规划批次，替换一次性 Markdown 功能列表路径且保持指定功能模式。直接调用方为 `GenerationWorkflow.executeClaimed`；前置：2.3、3.5。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=GenerationTaskStateMachineTest,FrozenFeatureServiceTest test`。 `[Req-ID]: REQ-CAG-001, REQ-BFA-005`

- [x] 4.2 扩展 `WebClientKnowledgeAgentAdapter.invoke`、`KnowledgeAgentInvocation` 和 `GenerationWorkflow.executeBatches`，使单功能生成仅请求 `functional-testcase-design`，并以对应 `read_skill` 成功事件为接收门禁。直接调用方为 `GenerationWorkflow.executeBatches`；前置：1.4、4.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,GenerationTaskStateMachineTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-CAG-001`

- [x] 4.2a 将单功能生成改为生成阶段隔离准备会话：准备流必须有精确 `functional-testcase-design` 的成功 `read_skill` SSE 证据，随后同一冻结范围的多个单功能请求复用该会话，并保留每次业务 SSE 的明确完成终态门禁。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,GenerationWorkflowBatchAcceptanceTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-CAG-001`

- [x] 4.3 扩展 `MarkdownGenerationResultParser.parse` 和 `GenerationWorkflow.executeBatches`，仅接收精确两 H2/两表/表头，验证当前冻结功能恰有 `_正向`、`_反向` 两行、步骤与预期一一对应、正式证据范围及 `依据通用经验，待确认`。直接调用方为 `GenerationWorkflow.executeBatches`；前置：1.5、4.2。验证：`./mvnw.cmd -pl backend -Dtest=MarkdownParsersTest,GenerationTaskStateMachineTest test`。 `[Req-ID]: REQ-CAG-002, REQ-CAG-003, REQ-CAG-006`

- [x] 4.4 扩展 `GenerationTaskRepository.acceptMarkdownBatch` 和 Markdown 持久化表，确保批次原子接收、失败重试替换旧尝试而不增加行数；为 `MarkdownBatchPersistenceIntegrationTest` 增加重复接收和替换回归。直接调用方为 `GenerationWorkflow.executeBatches`；前置：4.3。验证：`./mvnw.cmd -pl backend -Dtest=MarkdownBatchPersistenceIntegrationTest test`。 `[Req-ID]: REQ-CAG-001, REQ-CAG-002`

- [x] 4.5 扩展 `GenerationWorkflow.finishAndExport`、任务状态转换和 `GenerationTaskStateMachineTest`，使 `COMPLETED` 同时依赖材料遍历、审查终态、冻结集合、Skill 门禁、`N/2N`、批次接收和工作簿回读；在材料/审查有界工作项之间观察取消请求并正确区分 `PARTIAL`、`FAILED`、`CANCELLED`。直接调用方为 `GenerationWorkflow.executeBatches`；前置：2.3、3.5、4.4。验证：`./mvnw.cmd -pl backend -Dtest=FeatureAuditServiceTest,RequirementMaterialTraversalServiceTest,GenerationWorkflowAllModeTest,MarkdownBatchPersistenceIntegrationTest test`。 `[Req-ID]: REQ-CAG-004, REQ-KSI-002`

## 5. 两 Sheet 导出与业务进度纵向切片

- [x] 5.1 扩展 `ApachePoiWorkbookExporter.exportMarkdown` 的输入组装和 `MarkdownWorkbookExporterTest`，使第一 Sheet 只由耐久审查台账生成、第二 Sheet 只由已接收冻结批次生成，且精确保留两个 Sheet/表头/公式安全。直接调用方为 `GenerationWorkflow.finishAndExport`；前置：3.4、4.4。验证：`./mvnw.cmd -pl backend -Dtest=MarkdownWorkbookExporterTest test`。 `[Req-ID]: REQ-CAG-005, REQ-CAG-006`

- [x] 5.2 在 `GenerationWorkflow.finishAndExport` 和 `GenerationTaskRepository` 实现制品发布边界：仅审查覆盖及冻结完整时可发布明确标记的 `PARTIAL` 制品，材料/审查失败不得有制品。直接调用方为 `GenerationTaskController.download`；前置：4.5、5.1。验证：`./mvnw.cmd -pl backend -Dtest=GenerationTaskStateMachineTest,MarkdownWorkbookExporterTest test`。 `[Req-ID]: REQ-CAG-004, REQ-CAG-005`

- [x] 5.3 扩展 `GenerationTaskController.detail`、详情 DTO 和 `GenerationTaskDetail`，返回不含 KEE ID/游标的材料、审查、遗漏、冲突、冻结功能和用例进度计数；为 `backend/src/test/java/com/testcaseagent/task/GenerationTaskDetailTest.java` 新增契约测试。直接调用方为 `frontend/src/views/TaskDetailView.vue`；前置：2.2、3.4、4.5。验证：`./mvnw.cmd -pl backend -Dtest=GenerationTaskDetailTest test`。 `[Req-ID]: REQ-CWR-001`

- [x] 5.4 更新 `frontend/src/views/TaskDetailView.vue`、对应 Vitest 用例和样式 token，展示扫描、审查、冻结、生成、完成、部分完成、失败、重试、取消及业务化原因；保持可见焦点、失败输入保留和过期异步响应保护。直接调用方为任务详情路由；前置：5.3。验证：`npm --prefix frontend run test -- --run TaskDetailView.spec.ts`。 `[Req-ID]: REQ-CWR-001, REQ-CWR-002`

- [x] 5.5 为详情页新增 Playwright 浏览器检查脚本（`frontend/scripts/task_detail_completeness_acceptance.py`），验证 1024x768、1440x820、1920x1080 下的键盘 Tab/Enter、loading/error/empty/ready、失败恢复与不暴露技术标识。直接调用方为 5.4 的页面；前置：5.4。验证：先在独立终端启动本地 Vite 服务 `npm --prefix frontend run dev -- --host 127.0.0.1 --port 5175`，再执行 `python frontend/scripts/task_detail_completeness_acceptance.py`。 `[Req-ID]: REQ-CWR-001, REQ-CWR-002`

## 6. 收口验证与风险决策

- [x] 6.1 执行确定性 golden fixture（新建 `backend/src/test/java/com/testcaseagent/task/AllCompletenessGoldenFixtureTest.java`）：30 个清单条目、两个清单遗漏、一个需求遗漏、一个冲突、一个重复、一个拆分和一个证据不足，断言 100% 单元处置、稳定 `N` 和精确 `2N`。直接调用方为完整 `GenerationWorkflow`；前置：2.3、3.5、4.5、5.2。验证：`./mvnw.cmd -pl backend -Dtest=AllCompletenessGoldenFixtureTest test`。 `[Req-ID]: REQ-BFA-001~005, REQ-CAG-004`

- [x] 6.2 决定并记录全量回归风险：在执行前确认 MySQL 8/Testcontainers、前端依赖和临时 KEE 部署可用；若条件具备，执行 `./mvnw.cmd -pl backend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run lint`、`npm --prefix frontend run build`；若任一外部条件不具备，记录未执行原因及对发布的影响。直接调用方为发布验收；前置：6.1。验证：上述命令及 `openspec validate all-completeness-cross-audit --strict`。 `[Req-ID]: all`

- [ ] 6.3 在临时部署已单独验证、当前回滚的 KEE changes 后执行 V2.0 联合验收，记录 deployed image/commit、非 disabled sandbox mode、parsed-units 全量遍历、两个 `read_skill` 证据、100% 源单元处置、零未说明候选项、稳定 `N`、精确 `2N`、两个 Sheet 和重试/下载。直接调用方为真实 `GenerationWorkflow`；前置：6.1、6.2 及外部 KEE 临时部署。验证：有界 V2.0 创建任务、任务详情、重试和下载的非敏感运行记录。 `[Req-ID]: REQ-SMR-001~004, REQ-BFA-001~005, REQ-CAG-001~006, REQ-KSI-001~003, REQ-CWR-001~002`
