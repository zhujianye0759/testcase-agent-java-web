## 1. 固定外部合同的消费者测试

- [x] 1.1 针对 KEE 元数据和重复 preview 读取执行 V2.0 只读可行性验证；记录 preview 虽稳定，但无法证明当前读取中已持久化文本 Chunk 的完整枚举。 `[Req-ID]: REQ-SMR-001, REQ-SMR-003`

- [x] 1.2 新建 `backend/src/test/java/com/testcaseagent/knowledgeagent/ParsedUnitsWireMockTest.java`，以 `WebClientKnowledgeAgentAdapter` 的新 parsed-units 调用入口为直接调用方，固化 `GET /api/v1/knowledge/{knowledge_id}/parsed-units`、精确 data/unit 字段、`limit` 默认/100/超限钳制、HMAC cursor 续页、4 MiB 页和六类显式业务错误的 RED fixture。前置：1.1；不修改 KEE。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001, REQ-SMR-004`

- [x] 1.3 在 `ParsedUnitsWireMockTest` 为完整性门禁补齐 RED fixture：从第一页开始、`knowledge_id` 一致、全局 `unit_id` 唯一、`ordinal=1..total_units` 连续、无游标循环/缺口、仅最终 `next_cursor=null` 且 `complete=true`、`unit_too_large` 不截断及调用方明确报告替换后的首页重启。直接调用方为计划新增的 `RequirementMaterialReaderPort.readAll`；前置：1.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001~004`

- [x] 1.4 扩展 `backend/src/test/java/com/testcaseagent/knowledgeagent/WebClientKnowledgeAgentAdapterTest.java`，为审查与生成的 `agent-chat` 请求写 SSE RED fixture：精确 `skill_names`、对应 `read_skill` 的 `tool_call`/`tool_result` 成功、缺失、名称不符、失败、任意 `error`、截断和明确完成，并验证智能体发现的暂态/永久失败统一成为准备失败。直接调用方为 `WebClientKnowledgeAgentAdapter.invoke` 及计划新增的审查调用入口；前置：1.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest test`。 `[Req-ID]: REQ-KSI-001~003`

- [x] 1.5 扩展 `backend/src/test/java/com/testcaseagent/markdown/MarkdownParsersTest.java`，以 `MarkdownGenerationResultParser.parse` 为直接调用方，先写拒绝 JSON、代码围栏、改名 H2、改名/增删列、少表/多业务表和非 `<br>` 多项格式的 RED 用例。前置：无。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=MarkdownParsersTest test`。 `[Req-ID]: REQ-CAG-006`

## 2. 材料遍历与耐久台账纵向切片

- [x] 2.1 新建 `backend/src/main/java/com/testcaseagent/scope/RequirementMaterialReaderPort.java`、parsed-units DTO 和 `WebClientKnowledgeAgentAdapter` 实现；完成 1.2、1.3 的 GREEN，使其只消费固定 DTO、当前最新文档和既有范围授权。直接调用方为 `GenerationWorkflow.freezeAllFeatures` 的审查前置步骤；前置：1.2、1.3。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SMR-001~004`

- [x] 2.2 新建 Flyway 迁移 `backend/src/main/resources/db/migration/V9__create_material_inventory_and_audit_work.sql`，并扩展 `GenerationTaskRepository` 的材料单元、审查工作和尝试持久化方法；用 `backend/src/test/java/com/testcaseagent/task/MaterialInventoryPersistenceIntegrationTest.java` 证明幂等接收与租约到期后仅重领未完成工作。直接调用方为计划新增的材料审查编排服务；前置：2.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-BFA-001`

- [x] 2.3 在计划新增的 `backend/src/main/java/com/testcaseagent/featureaudit/RequirementMaterialTraversalService.java` 实现读取、逐页校验、原子写入和调用方明确替换后的清空重启；不得把 preview、RAG 回答或部分单元写成完整。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：2.1、2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest,MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001`

## 3. 审查、冻结和 Skill 门禁纵向切片

- [x] 3.1 新建 `backend/src/main/java/com/testcaseagent/featureaudit/FeatureCandidateScanner.java` 及 `FeatureCandidateScannerTest`，从耐久材料单元有界提取功能清单候选项，保留重复展示序号/重复出现而不伪造 Excel 原生行。直接调用方为计划新增的 `FeatureAuditService`；前置：2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureCandidateScannerTest test`。 `[Req-ID]: REQ-BFA-001, REQ-BFA-004`

- [x] 3.2 在 `backend/src/main/java/com/testcaseagent/featureaudit/RequirementCandidateScanner.java` 及 `RequirementCandidateScannerTest` 实现两遍有界需求扫描、第二遍新增项和未收敛失败，不允许未收敛单元进入终态。直接调用方为 `FeatureAuditService`；前置：2.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=RequirementCandidateScannerTest test`。 `[Req-ID]: REQ-BFA-002`

- [x] 3.3 新建 `FeatureAuditService`、审查结论 repository 方法及 `FeatureAuditServiceTest`，在调用 KEE 审查前发送且仅发送 `feature-scope-reconciliation`，仅在 SSE 观察到同名 `read_skill` 成功时接收双向结论。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：1.4、2.2、3.1、3.2。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,FeatureAuditServiceTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-BFA-003, REQ-BFA-004`

- [x] 3.3a 联合验收发现每个材料单元均依赖模型自行 `read_skill` 会造成全量审查失败后，改为每个材料单元及最终核对各自的隔离准备/业务会话对：准备流必须有精确 `feature-scope-reconciliation` 的成功 `read_skill` SSE 证据，随后仅在该同一会话发送该项业务请求并立即关闭；已完成审查恢复时不预建会话。准备阶段出现名称不符的 `read_skill`、任意 `error`、非 `read_skill` 工具、永久合同错误、范围错误或智能体发现失败时，记录当前工作失败并立即终止审查；暂态传输错误最多三次新会话。更新 WireMock/FeatureAuditService RED→GREEN 回归。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest test`。 `[Req-ID]: REQ-KSI-001~003`

- [x] 3.3b 联合验收确认 KEE 会将非 casual 的准备说明预执行为 RAG 后，准备请求保持冻结范围但使用固定 `你好` token，仍仅传入阶段精确 `skill_names`。接受 KEE 同一 `tool_call_id` 的 `arguments:null` `read_skill` 声明，但仅在同 ID 后续精确名称、成功结果和完成终态齐备时通过；声明或精确调用悬空、ID 不一致、错误 Skill/工具/结果/error 均失败关闭。更新 WireMock RED→GREEN。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest test`。 `[Req-ID]: REQ-KSI-001~003`

- [x] 3.3c 将准备和业务调用收敛到 KEE `isolated-skill` 专用入口，不对 404 回退旧 agent-chat；每次调用都验证同 ID `read_skill` 声明、精确参数、成功结果及完成终态，并拒绝除 `read_skill`/`execute_skill_script` 外的任意工具。更新 WireMock RED→GREEN。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest test`。 `[Req-ID]: REQ-KSI-004`

- [x] 3.3d 将 `documentId`、`unitId`、`candidateIds` 从任务详情、最终 Markdown 和两 Sheet 导出投影中确定性清理，并让导出器拒绝仍含机器 token 的输入；更新 MySQL/Apache POI 集成回归。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=MarkdownBatchPersistenceIntegrationTest test`。 `[Req-ID]: REQ-CWR-003`

- [x] 3.3e 联合验收发现模型会将中间坐标与证据正文用 `<br>` 粘连或把证据塞入分类列后，固定每个非空扫描行的四列边界：第三列仅为问题分类，第四列必须以 `documentId=<exact>; unitId=<exact>; ` 开头，第二个坐标 token 后用分号接证据正文；不得放宽 parser、SSE 或坐标门禁。更新功能清单/需求两遍扫描 RED→GREEN。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureCandidateScannerTest,RequirementCandidateScannerTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest test`。 `[Req-ID]: REQ-BFA-001~004`

- [x] 3.3f 为同一材料扫描工作第 2/3 次重领补充安全格式纠错反馈：在认领事务中读取该 work 最近一次已持久化 `failure_summary`，每次重领均附加固定、全面的严格 Markdown 基线（首字符 `#`、首行精确第一表标题且无前置 prose、两张表连续且无自我复核/撤销/重复表）；白名单命中才追加固定重点，首次无反馈，未知/空/缺失摘要只给全面基线。基线只能用自然语言指向本次提示的坐标标记，不得含占位符、动态坐标、原始异常、URL、路径、凭据或机器标识。保持最多三次、失败关闭、扫描器/最终核对严格合同和不可变台账不变。验证：`FeatureAuditServiceTest#tellsAReclaimedHeadingFailureToRemoveAllLeadingProseBeforeTheExactFirstHeading`、`FeatureAuditServiceTest#tellsAReclaimedSecondHeadingFailureToOutputOnlyTwoContiguousTablesWithoutSelfReview` 和 `FeatureAuditServiceTest#retainsTheCompleteFormatBaselineAcrossAlternatingStrictMarkdownFailures` 先 RED 后 GREEN，再运行 `FeatureAuditServiceTest,MaterialInventoryPersistenceIntegrationTest,FeatureCandidateScannerTest,RequirementCandidateScannerTest`。 `[Req-ID]: REQ-BFA-006`

- [x] 3.3g 将最终双向核对改为全量上下文、固定 16 目标候选页和服务端稳定 anchor 归并：每页保留既有严格两表 parser，`candidateIds` 仅绑定本页目标，`groupAnchorId` 仅内部使用；强制最早自指 anchor、逐字相同类型/路径、全局 union、坐标顺序与最终序号稳定。分页与冻结层共用 NFKC、首尾 strip、连续空白折叠为一个空格并转为小写的业务路径身份：同一归一化路径必须映射同一最早 anchor/同一分类，且单个拆分结论内必须互异；分页在持久化前复用冻结层完整路径结构校验（非拆分不得含 literal `<br>`；拆分须 literal `<br>` 至少两项、每项非空纯文本且归一化互异）。每页最多三次安全重试，全部页面成功前不持久化，未知/非法/非自指 anchor、组漂移、归一化路径冲突、格式错误或耗尽均失败关闭。将 `groupAnchorId` 从任务详情、Markdown 和工作簿投影清理，冻结层继续拒绝不同自锚组形成的重复业务路径。验证：`FeatureAuditServiceTest`、`MarkdownBatchPersistenceIntegrationTest#exportsTaskOwnedReviewConclusionsAndAllAcceptedBatchCasesWithoutTechnicalCandidateTokens`、`FrozenFeatureServiceTest#rejectsARepeatedNormalizedBusinessPathAcrossSeparateConclusions`。 `[Req-ID]: REQ-BFA-007, REQ-CWR-003`

- [x] 3.3h 真实 V2.0 最终核对确认模型将业务层级以单独的 `>` 表示；在共享业务路径门禁中允许该可读分隔符，同时继续拒绝 `<`/标记，不放宽 Markdown、候选精确绑定、anchor、归一化去重、冲突或机器 token 门禁。以冻结服务作为领域边界，先写 `FrozenFeatureServiceTest#acceptsGreaterThanAsAPlainTextHierarchySeparator` RED，再证明既有 markup 拒绝仍为 GREEN。 `[Req-ID]: REQ-BFA-003, REQ-BFA-005, REQ-BFA-007`

- [x] 3.3i 为尚未创建批次的审查失败保存并投影安全业务原因：运行日志仅记录经脱敏的合同原因，任务结果快照只存业务摘要；详情/列表不得只返回空摘要，更不得泄漏模型输出、提示词、候选坐标或堆栈。以 `MarkdownBatchPersistenceIntegrationTest#exposesASafeAuditingFailureSummaryWhenNoGenerationBatchExists` 先 RED 后 GREEN。 `[Req-ID]: REQ-CWR-002`

- [x] 3.3j 真实 V2.0 最终核对发现模型在 16 目标页成功终态中遗漏第 16 行；保持“每页最多 16”合同和每目标恰好一行门禁，将固定目标页收紧为 8，仍发送全量候选上下文、仍最多三次失败关闭重试。先让 17 候选分页测试在 16 页大小下 RED，再以 8/8/1 页 GREEN，并断言每页目标数不超过 8。 `[Req-ID]: REQ-BFA-007`

- [x] 3.3k 真实 V2.0 8 项分页发现相同归一化业务路径跨页会因后页未看到前页已接受分类/anchor 而漂移；后续页请求必须携带已接受 self-anchor 的业务路径、分类和 anchor，要求相同路径沿用既有结论。保持 Java 全局归一化、冲突和 anchor 校验不变；先让跨页归组提示上下文断言 RED，再 GREEN。 `[Req-ID]: REQ-BFA-007`

- [x] 3.3l 真实 V2.0 后续页虽复用既有 anchor 仍会改写同一业务路径，触发同一 anchor 精确类型/路径门禁；在已接受跨页结论提示中要求相同归一化路径逐字复制既有 `issueCategory` 与 `businessPath` 并沿用 anchor。先让提示断言 RED，再 GREEN；不改变 Java 的精确比较或失败关闭。 `[Req-ID]: REQ-BFA-007`

- [x] 3.3m 针对真实 V2.0 中“可解析但后页重判已接受路径”的失败，新增脱敏 16 候选 replay：前页 8 条已接受、后页 8 条同路径改分类/anchor。仅当 Java 从已接受结论为每个当前漂移目标派生精确 `targetCandidateId`、分类、业务路径和 anchor 重试绑定时才 GREEN；不得回灌原始异常或未接受模型文本，严格 parser、全局路径/anchor/冲突门禁不变。验证：`FeatureAuditServiceTest#retriesEveryCrossPageConclusionDriftWithItsExactAcceptedBinding` 先 RED 后 GREEN，并运行 `FeatureAuditServiceTest`。 `[Req-ID]: REQ-BFA-007`

- [x] 3.3n 针对真实 V2.0 中同页稳定漏掉的重复路径目标，使用既有业务路径归一化算法确认其与同页代表相同而不在 `candidateIds` 中；在调用模型前按候选功能名称预分组，只发送每组最早代表，并在代表单一路径/绑定通过后为每个同组成员确定性重建精确文档、单元、候选与稳定顺序。独立路径缺失、代表路径漂移、代表“拆分”结论或代表绑定错误保持失败关闭；本期不加入遗漏补偿轮次。验证：`FeatureAuditServiceTest#projectsOneRepresentativeConclusionToEverySameNormalizedFeatureTextMember`、`FeatureAuditServiceTest#failsClosedForAnIndependentTargetMissingFromTheRepresentativeResponse`、`FeatureAuditServiceTest#failsClosedWhenAGroupedRepresentativeDoesNotBindItsOwnMaterialCoordinate`、`FeatureAuditServiceTest#doesNotProjectASplitRepresentativeOntoASingleRepeatedFeaturePath` 先 RED 后 GREEN，并运行 `FeatureAuditServiceTest,MarkdownBatchPersistenceIntegrationTest`。 `[Req-ID]: REQ-BFA-003, REQ-BFA-007, REQ-CWR-003`

- [x] 3.3o 修复同路径投影的跨来源可读证据错配，并在无批次 ALL 审查失败重试开始时原子清除旧任务失败快照：每个投影成员保留自身候选的可读证据，代表行仅决定分类/路径/anchor；详情和列表仅展示当前失败而非历史失败。以 `FeatureAuditServiceTest#projectsOneRepresentativeConclusionToEverySameNormalizedFeatureTextMember` 和 `MarkdownBatchPersistenceIntegrationTest#requeuesAnUnbatchedAllDiscoveryFailureWithoutRetainingItsHistoricalFailureSummary` 先 RED 后 GREEN。验证：`FeatureAuditServiceTest,MarkdownBatchPersistenceIntegrationTest`。 `[Req-ID]: REQ-BFA-003, REQ-BFA-007, REQ-CWR-002`

- [x] 3.3p 新建 `WorkflowDiagnostics`/凭据脱敏测试及 `backend/src/main/resources/logback-spring.xml`，并接入 `FeatureAuditService.reconcilePage`、`GenerationWorkflow.executeBatches`：最终任务快照仅保存页号/总页数/次数/封闭类别；`workflow.diagnostics` 滚动文件按 task/stage/page/attempt 保留提示词、SSE 终态 Markdown 与校验上下文，但写入前删除 API Key、密码和鉴权头，且绝不进入详情/列表/工作簿。直接调用方为审查与批次编排。以 `WorkflowDiagnosticsTest`、`FeatureAuditServiceTest`、`GenerationWorkflowAllModeTest` 先 RED 后 GREEN。 `[Req-ID]: REQ-CWR-004`

- [x] 3.3q（历史实现，已由 3.3s 修订/替代）在 `FeatureAuditService.reconcilePage` 增加仅限“三次尝试均为 `MISSING_TARGET`”的逐项补偿：丢弃页级未接收响应，并按原稳定顺序对每个代表目标至多三次单目标 isolated-skill 核对；任一页级尝试为严格 Markdown、anchor、绑定、路径或其他封闭类别则不得补偿。每项继续执行既有精确绑定、严格 Markdown、路径和 anchor 校验，全部成功才原子合并该页。正常页、其他失败类别、任一补偿失败均不得触发降级或持久化部分页。真实 V2.0 第 22/30 页的固定 `Only SPLIT conclusions may contain multiple business paths` 错误仅补充初始/重试提示：章节号、条款号和目录编号只留在证据对照列，非拆分业务路径删除且不得复制 `<br>` 后编号；不裁剪回包、不放宽门禁、不启用补偿。以 `FeatureAuditServiceTest` 八项互异目标 replay、混合失败不补偿、正常页、补偿失败、绑定/顺序、机器 token 及该路径纠错测试先 RED 后 GREEN。其“仅 `MISSING_TARGET`”及绑定/路径不补偿的历史限制已被 3.3s 的“三次累计批准类别”规则替代。 `[Req-ID]: REQ-BFA-007, REQ-CWR-004`

- [x] 3.3r 真实 V2.0 第 15/30 页前两次目标覆盖失败、第三次代表来源坐标绑定失败后，补全最终核对本页代表目标行的 `candidateId`、`documentId`、`unitId` 和可读功能文本；初始及固定 `Grouped representative evidence must bind its exact documentId and unitId` 重试提示均要求逐字复制同一目标绑定行，禁止从全量同名/同路径邻居复制坐标。以两个同路径不同来源候选的 RED→GREEN 夹具证明邻居坐标被拒、第二次自身坐标通过且不补偿；混合目标覆盖→代表绑定三次继续失败关闭。 `[Req-ID]: REQ-BFA-007`

- [x] 3.3s 将页级逐项补偿修订为仅限三次累计均属于批准集合 `MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE`（允许混合）的孤立复核；任一 `STRICT_MARKDOWN`、`ANCHOR_CONFLICT`、`NORMALIZED_PATH_CONFLICT`、`UNKNOWN_CONTRACT` 或其他类别零复核并失败关闭。保持每批次最多 8 个代表目标、全量上下文、精确绑定、既有 parser/path/anchor/cross-page 校验、每项最多三次、全通过原子合并、任一失败无部分持久化；不得自动修正分类/路径/坐标或放宽冻结/`2N`。安全摘要和用户可见场景改为“第 X/Y 个功能审核批次”（非源文件页），内部 page 技术字段可保持。以 `FeatureAuditServiceTest` 覆盖三次 BUSINESS_PATH_STRUCTURE、三种批准类别混合、各个非批准类别零复核、逐项失败零持久化及既有三次 MISSING_TARGET 回归；以受影响详情/工作流测试覆盖安全摘要。验证：`WorkflowDiagnosticsTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest`、受影响安全摘要测试、`openspec validate all-completeness-cross-audit --strict`、`git diff --check`。 `[Req-ID]: REQ-BFA-007, REQ-CWR-004`

- [x] 3.3t（历史实现，已由 3.3u 修订/替代）为同一功能审核批次 `NORMALIZED_PATH_CONFLICT` 的第二、三次 bulk 重试追加冲突目标 `targetCandidateId` 的精确反馈；其未定义按组全量目标、稳定最早目标和固定规则的限制由 3.3u 替代。 `[Req-ID]: REQ-BFA-007, REQ-CWR-004`

- [x] 3.3u 将同一功能审核批次 `NORMALIZED_PATH_CONFLICT` 的第 2/3 次 bulk 反馈修订为逐冲突组只输出全部 `targetCandidateId`、按全量稳定候选顺序确定的 `earliestTargetCandidateId`、同一实际值的 `requiredGroupAnchorId` 与固定规则，多个组清晰隔离，不回灌未接收回包的路径、分类或正文。规则要求：仍判断同一路径则每行 `groupAnchorId` 必须逐字复制该组 `requiredGroupAnchorId` 的实际值，且分类和完整路径一致；判断不同则以正式证据给出真实可区分路径和各自合法 anchor。Java 只验证、绝不改写；跨批已接受先例保持既有精确绑定。该类别仍非批准，三次耗尽零逐项、零持久化、失败关闭。以 `FeatureAuditServiceTest` 覆盖同类别不同 self-anchor 的单组实际最早 anchor、双组隔离及各自实际 anchor、三次冲突零逐项零持久化、一致组不误提示及既有跨批先例回归；先 RED 后 GREEN。验证：`WorkflowDiagnosticsTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest`、`openspec validate all-completeness-cross-audit --strict`、`git diff --check`。 `[Req-ID]: REQ-BFA-007, REQ-CWR-004`

- [x] 3.3v 在 `validatePageConclusions` 以最终 projected 当前行确认 `ANCHOR_CONFLICT` 为“错误借用已接受前批 anchor 且类型或完整路径不逐字相等”后，为第 2/3 次 bulk 反馈逐目标仅输出 `targetCandidateId`、`rejectedGroupAnchorId`、实际 `requiredSelfAnchorId`，并要求不复用已接受先例时逐字 self-anchor、确属旧组时逐字复用分类/完整路径/旧 anchor；不得回灌未接收回复业务值，Java 仅验证不改写。保持 `ANCHOR_CONFLICT` 非批准、三次零逐项零持久化失败关闭。以 `FeatureAuditServiceTest` 覆盖四个错误当前目标全量列出且不混 self 行、修正 self-anchor 通过、完全匹配 accepted anchor 不提示、三次错误零 singleton/零持久化，并回归 3.3u、批准三次和跨批精确绑定。先 RED 后 GREEN。验证：`WorkflowDiagnosticsTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest`、`openspec validate all-completeness-cross-audit --strict`、`git diff --check`。 `[Req-ID]: REQ-BFA-007, REQ-CWR-004`

- [x] 3.4 在 `FeatureAuditService` 与 `GenerationTaskRepository` 实现每个候选项恰好一个可追溯结论：匹配、`功能清单遗漏`、`需求未覆盖该功能点`、冲突、拆分、合并、重复或证据不足；正式事实只用 document/chunk 证据。直接调用方为冻结服务；前置：3.3。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureAuditServiceTest test`。 `[Req-ID]: REQ-BFA-003, REQ-BFA-004`

- [x] 3.5 新建 `backend/src/main/java/com/testcaseagent/featureaudit/FrozenFeatureService.java` 与 `FrozenFeatureServiceTest`，仅在所有单元/候选/关系已终态时原子冻结稳定 ID、顺序、来源和可生成性，且重试复用冻结结果。直接调用方为 `GenerationWorkflow.freezeAllFeatures`；前置：3.4。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FrozenFeatureServiceTest,MaterialInventoryPersistenceIntegrationTest test`。 `[Req-ID]: REQ-BFA-005`

## 4. 单功能生成、两表接收与任务完成纵向切片

- [x] 4.1 重构 `GenerationWorkflow.freezeAllFeatures` 和 `GenerationTaskRepository.planDiscoveredBatches`，以 `FrozenFeatureService` 的结果规划批次，替换一次性 Markdown 功能列表路径且保持指定功能模式。直接调用方为 `GenerationWorkflow.executeClaimed`；前置：2.3、3.5。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=GenerationTaskStateMachineTest,FrozenFeatureServiceTest test`。 `[Req-ID]: REQ-CAG-001, REQ-BFA-005`

- [x] 4.2 扩展 `WebClientKnowledgeAgentAdapter.invoke`、`KnowledgeAgentInvocation` 和 `GenerationWorkflow.executeBatches`，使单功能生成仅请求 `functional-testcase-design`，并以对应 `read_skill` 成功事件为接收门禁。直接调用方为 `GenerationWorkflow.executeBatches`；前置：1.4、4.1。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,GenerationTaskStateMachineTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-CAG-001`

- [x] 4.2a 将单功能生成改为每批次独立的准备/业务会话对：准备流必须有精确 `functional-testcase-design` 的成功 `read_skill` SSE 证据，随后仅在同一会话发送该冻结功能的业务请求并立即关闭；智能体发现或准备耗尽时终止任务，不准备或发送后续批次。保留每次业务 SSE 的明确完成终态门禁。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=WebClientKnowledgeAgentAdapterTest,GenerationWorkflowBatchAcceptanceTest test`。 `[Req-ID]: REQ-KSI-001~003, REQ-CAG-001`

- [x] 4.3 扩展 `MarkdownGenerationResultParser.parse` 和 `GenerationWorkflow.executeBatches`，仅接收精确两 H2/两表/表头，验证当前冻结功能恰有 `_正向`、`_反向` 两行、步骤与预期一一对应、正式证据范围及 `依据通用经验，待确认`。直接调用方为 `GenerationWorkflow.executeBatches`；前置：1.5、4.2。验证：`./mvnw.cmd -pl backend -Dtest=MarkdownParsersTest,GenerationTaskStateMachineTest test`。 `[Req-ID]: REQ-CAG-002, REQ-CAG-003, REQ-CAG-006`

- [x] 4.4 扩展 `GenerationTaskRepository.acceptMarkdownBatch` 和 Markdown 持久化表，确保批次原子接收、失败重试替换旧尝试而不增加行数；为 `MarkdownBatchPersistenceIntegrationTest` 增加重复接收和替换回归。直接调用方为 `GenerationWorkflow.executeBatches`；前置：4.3。验证：`./mvnw.cmd -pl backend -Dtest=MarkdownBatchPersistenceIntegrationTest test`。 `[Req-ID]: REQ-CAG-001, REQ-CAG-002`

- [x] 4.4a 在 `GenerationTaskRepository.retryFailedBatches`、`GenerationWorkflow.executeBatches` 和任务详情投影验证失败/进程恢复后的批次断点续跑：前序已接收的冻结批次及其两条用例行保持不变，失败批次重试只能创建新尝试并继续该批次，只重新领取失败或未接收批次，不能重新调用前序已接收功能；冻结 N、顺序及最终精确 `2N` 不变，任务详情继续以业务计数展示已接收/期望用例。直接调用方为 `GenerationTaskRepository.retryFailedBatches`。验证：`MarkdownBatchPersistenceIntegrationTest#atomicallyReplacesOnlyTheRetriedBatchWithItsTwoCaseRowsAndPreservesAttemptHistory`、`GenerationWorkflowAllModeTest`。 `[Req-ID]: REQ-CAG-007, REQ-CWR-001`
- [x] 4.4b 对真实 V2.0 中 43 个失败生成批次增加仅限 ALL 冻结功能第 2/3 次尝试的安全纠错提示：从同一批次紧邻上一失败 attempt 读取既有原因，只将七类固定校验消息白名单映射为固定中文规则，未知或可疑原因仅给不含原文的通用提醒；不得回灌失败原文、模型响应、异常、URL 或凭据，不改数据库结构，不放宽 parser/validator/2N/finalization。首轮 ALL 和既有指定功能提示保持不变，已接收批次零 prepare/invoke/覆盖。以 `GenerationWorkflowAllModeTest` 先 RED 后 GREEN，并验证 `WorkflowDiagnosticsTest,FeatureAuditServiceTest,GenerationWorkflowAllModeTest`、OpenSpec strict 与 `git diff --check`。 `[Req-ID]: REQ-CAG-007, REQ-CWR-004`
- [x] 4.4c 针对第 2 次真实重试后 14 个批次均把通用经验与 `<br>candidateIds` 混写的固定失败，将该白名单纠错消歧为互斥二选一：正式材料使用可读摘要和允许的 `candidateIds`，通用经验单元格只填写 `依据通用经验，待确认` 并立即结束，禁止追加 `<br>`、证据标记、标签、引号、句号或其他文字；不得读取或回灌实际失败单元格，不改 validator。以 `GenerationWorkflowAllModeTest` 先 RED 后 GREEN，并验证三组纯 Java 门禁、OpenSpec strict 与 `git diff --check`。 `[Req-ID]: REQ-CAG-007, REQ-CWR-004`

- [ ] 4.4d 将失败批次重试的筛选、条件重排、新 attempt、终态制品清理和任务重排收敛为单一事务；新 attempt 写入失败时回滚全部变更，并保持已接收批次与无批次 ALL 重试行为不变。以无容器事务 seam 先 RED 后 GREEN，并补充 MySQL 重试、尝试上限、相邻失败原因及两个受控并发重试仅创建一个新 attempt 的回归。 `[Req-ID]: REQ-CAG-007`

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
  - 2026-08-17 验收记录：`evidence/joint-acceptance-20260817.md`。当前 170/175 个可生成批次接收、340/350 条用例，5 个批次三次耗尽，故本任务保持未完成。
