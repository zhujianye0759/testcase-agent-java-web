## 1. 现场 RED 与业务边界

- [x] 1.1 从受控任务 `a422272c-a993-4553-8c46-58a89e39c20b` 的只读事实/证据和已接收正文建立独立 validator 夹具，分别证明 formal title、precondition、step.action、step.expected 的无依据具体化在当前实现中被错误接受。 `[Req-ID]: REQ-FTG-001`
- [x] 1.2 增加正向与状态控制：相同术语在绑定正式来源明确出现时通过；无依据内容只有显式 `pending_confirmation` 且 missing_information 非空时可保留，并不贡献正式覆盖。 `[Req-ID]: REQ-FTG-001, REQ-FTG-002`

## 2. 正式支持闭包与失败关闭实现

- [x] 2.1 扩充 Java `AcceptedFact`/恢复查询，按同一 task 恢复完整 requirement fact 字段和绑定 parsed-unit 正文；在 `WorkItem` 冻结 fact/evidence 到正文的不可变支持闭包。不得修改 KEE 字段。 `[Req-ID]: REQ-FTG-003`
- [x] 2.2 在 `FunctionalTestcaseResultValidator` 实现 NFKC 规范化、固定非业务包装和按字段语义的直接片段校验；不得使用领域禁词表或零散字符拼接。 `[Req-ID]: REQ-FTG-001`
- [x] 2.3 将支持闭包接入生产 coordinator 和原子 acceptance store；grounding 失败使用 `business_validation_failed`、零接收且不加入模型瞬时错误自动重试白名单。 `[Req-ID]: REQ-FTG-001, REQ-FTG-003`

## 3. 持久化与交付一致性

- [x] 3.1 增加 MySQL/Testcontainers 集成测试，证明任一字段越界时 test point、case、step、binding 均不写入；通过结果仍原子持久化。 `[Req-ID]: REQ-FTG-003`
- [x] 3.2 增加重启恢复测试，证明上游完成后从数据库恢复同一支持闭包、不会重复 KEE 工作，且详情与双 Sheet Excel 只显示已通过的数据。 `[Req-ID]: REQ-FTG-003`
- [x] 3.3 记录 artifact `bc0972fc-f860-4f2a-8903-72c889434a76` 的 H2:H9 是 shared-string 空值、artifact-tool 将索引 33 误画为数据的只读结论；不得据此修改 exporter。 `[Req-ID]: REQ-FTG-003`

## 4. 本地门禁

- [x] 4.1 运行 validator/coordinator/acceptance store/详情/Excel 聚焦测试及相关 MySQL/Testcontainers 回归。 `[Req-ID]: REQ-FTG-001, REQ-FTG-002, REQ-FTG-003`
- [x] 4.2 运行 `openspec validate enforce-formal-testcase-grounding --strict`、`git diff --check` 和敏感信息形态检查；未全部通过前不提交、部署或创建新验收任务。 `[Req-ID]: all`

## 5. KEE 正式支持正文输入

- [x] 5.1 建立 DTO、HTTP wire 和生产 coordinator RED：证明当前 `functional-testcase-design` 请求缺少 `formal_supports`，且失败任务 `58218593-cdbe-4f80-9e2d-17bce1bf1f86` 已因 KEE 无法得到完整正式正文而由 Java 以 `business_validation_failed` 零接收。不得读取或保存模型原始响应。 `[Req-ID]: REQ-FTG-004`
- [x] 5.2 定义独立不可变 input `FormalSupport`，扩充 `FunctionalTestcaseDesignInput` 严格合同和 WebClient 请求；只增加 `formal_supports`，不改变结果、数据库或普通 Agent。 `[Req-ID]: REQ-FTG-004`
- [x] 5.3 由 coordinator 从当前测试点绑定的耐久 `AcceptedFact` 构造确定顺序支持正文；限制 fact/evidence 闭包，缺失/空/越界在网络前失败关闭，不信任 test point description。 `[Req-ID]: REQ-FTG-003, REQ-FTG-004`
- [x] 5.4 保持现有 formal validator、pending/coverage、`business_validation_failed` 非重试和原子零接收语义；增加账号/手机号、Token 正反控制及重启恢复一致性测试。 `[Req-ID]: REQ-FTG-001~004`
- [x] 5.5 运行 input contract、wire、coordinator、validator、MySQL 聚焦，以及 `openspec validate enforce-formal-testcase-grounding --strict`、`git diff --check` 和敏感信息形态检查；KEE 对应资产 GREEN 前不得部署或创建新验收任务。 `[Req-ID]: all`

## 6. Requirement fact 直接依据门禁

- [x] 6.1 从最终任务 `cef21af2-20c6-446a-950b-c464dc17e951` 建立 requirement-review 原子接收 RED：`fact-00b8...` 的删词、拆写和新增状态必须拒绝；逐一覆盖 fact 全部叙述字段族，并以 `fact-9439...` 的直接原文片段作为正控。失败时 fact/finding/binding 零接收。 `[Req-ID]: REQ-FTG-005`
- [x] 6.2 在当前 review work 的同 task/material/slice 冻结闭包中解析 cited evidence 正文，并在 `RequirementMaterialReviewValidator` 对完整 result 先校验后持久化；失败稳定分类为 `business_validation_failed` 且不重试。不得放宽 testcase validator。 `[Req-ID]: REQ-FTG-005`
- [x] 6.3 运行 review validator、coordinator、MySQL acceptance、testcase grounding 回归，以及 OpenSpec strict、diff 和敏感信息门禁；KEE requirement review 与 testcase input-aware 校验完成前不得部署或创建任务。 `[Req-ID]: all`

## 7. 冻结高粒度 DTO 与 validator

- [x] 7.1 增加请求 DTO/Wire RED：可选 author/date、support evidence keys、formal/general supports 和 fact/evidence 精确闭包；证明旧请求缺字段或越界仍可发送。 `[Req-ID]: REQ-FTG-006`
- [x] 7.2 增加结果 DTO RED：冻结 testcase 全字段、优先级与四类输入枚举、显式空初始化数组、增强步骤、author/date 精确回显和未知字段拒绝。 `[Req-ID]: REQ-FTG-006`
- [x] 7.3 扩充 `FunctionalTestcaseResultValidator`：formal facts/evidence 精确闭合、完整值相等、标点边界和五条通用语句；pending 仍要求 missing information 且不计覆盖。 `[Req-ID]: REQ-FTG-006`
- [x] 7.4 扩充 requirement review DTO/validator：13 根因、影响范围、真实坏例 quote、待确认好例、中文分析和有界重复根因失败关闭。 `[Req-ID]: REQ-FTG-007`

## 8. 增量迁移、原子接收与跨调用合并

- [x] 8.1 读取 V12 真实 schema 后新增首个空闲 Flyway 迁移和命名测试，只增加表/列/索引，不修改 V12 或 Flyway history。 `[Req-ID]: REQ-FTG-008, REQ-FTG-009`
- [x] 8.2 在 acceptance store 事务内持久化高粒度 testcase/finding；任一校验或数据库失败零部分写入，重启恢复字段一致。 `[Req-ID]: REQ-FTG-009`
- [x] 8.3 以 task+root cause 耐久唯一身份合并跨切片范围/证据，证明并发幂等、相同根因合并、不同根因隔离和 proposed good 永不进入正式链路。 `[Req-ID]: REQ-FTG-007, REQ-FTG-008`
- [x] 8.4 接通 coordinator 和 mapper，确保阶段间只使用已验收持久化记录，function path 由 confirmed mapping 派生。 `[Req-ID]: REQ-FTG-006~009`

## 9. 同源详情和恰好双 Sheet Excel

- [x] 9.1 增加 API/投影 RED：显示高粒度 testcase、按根因合并 review、formal/pending 与中文来源，不暴露 raw JSON、内部枚举或机器键；保留旧 FEATURE/Markdown。 `[Req-ID]: REQ-FTG-007~009`
- [x] 9.2 增加 exporter RED：两个固定 Sheet、评审合并字段、高粒度用例字段、步骤/预期/评价对应、公式注入防护和页面/Excel 数量一致。 `[Req-ID]: REQ-FTG-009`
- [x] 9.3 更新 Vue 详情页，复用现有 PC 详情模板和语义 token，覆盖 ready/empty/error、1440x820 与相关窄宽。 `[Req-ID]: REQ-FTG-009`

## 10. 验证、提交与部署

- [x] 10.1 运行 DTO/Wire/validator/coordinator/acceptance/export/detail 聚焦测试及 MySQL/Testcontainers/Flyway 回归。 `[Req-ID]: REQ-FTG-006~009`
- [x] 10.2 运行受影响后端回归、前端 unit/typecheck/lint/build、OpenSpec strict、git diff --check、敏感形态和主任务事务/权限/泄漏复审。 `[Req-ID]: all`
- [x] 10.3 本地提交、不推送不归档；从干净 worktree 构建并替换 Java 8082 和 5173，保存页面证据。不得创建或重试最终业务任务。 `[Req-ID]: all`

## 11. Requirement fact PDF 布局空白容错

- [x] 11.1 增加 validator RED：PDF 换行拆词和普通空格差异可接受；删除标点、跨 evidence 拼接仍拒绝；bad-source quote 删除内部空格仍拒绝。 `[Req-ID]: REQ-FTG-005`
- [x] 11.2 拆分 fact 与 bad-source quote normalizer；fact 使用 NFKC、`Locale.ROOT` 小写并移除 Unicode whitespace/space chars，quote 保持默认 `\s+` 压缩与 `strip`。 `[Req-ID]: REQ-FTG-005`
- [x] 11.3 运行 validator 聚焦及相关 package 回归、OpenSpec strict、`git diff --check` 和 hunk 级主任务复审；不构建、部署或执行业务 retry。 `[Req-ID]: REQ-FTG-005`

## 12. 材料评审 response-too-large 原地二分

- [x] 12.1 增加协调器 RED：32-unit `response_too_large` 只执行两个确定性连续子窗口；覆盖 33/64/65 边界、原样 unit 字段、最小窗口失败和重启跳过父/已完成子项。 `[Req-ID]: REQ-FTG-010`
- [x] 12.2 增加 MySQL RED 与增量迁移：父 attempt、父 `SPLIT` 和两个子 work 原子提交；验证回滚、并发唯一、冻结闭包冲突拒绝、既有 accepted rows/hash 不变。 `[Req-ID]: REQ-FTG-010`
- [x] 12.3 实现 split-aware review coordinator/store 与完整 inventory 恢复；聚合、材料阶段完成和显式 retry 只以叶子 work 判断，不把 `response_too_large` 加入自动模型重试。 `[Req-ID]: REQ-FTG-010`
- [x] 12.4 运行协调器、store、Flyway、retry/heartbeat/recovery 相关测试，OpenSpec strict、diff/sensitive 门禁和完整差异复审；KEE length 映射部署前不得调用真实 task retry。 `[Req-ID]: REQ-FTG-010`

## 13. 拆分树失败叶子的显式恢复

- [x] 13.1 增加 MySQL RED：一个零写入 FAILED 叶子与多个 QUEUED 兄弟并存时，当前资格判断错误拒绝；恢复只重置失败叶子并保留兄弟、成功哈希和历史 attempt。 `[Req-ID]: REQ-FTG-011`
- [x] 13.2 最小修改 `structuredRetryEligibility`：锁定全部未完成 work，要求唯一 FAILED、其余仅 QUEUED，且全部无 accepted hash/八类业务行；多个失败、RUNNING、部分写入和并发漂移继续拒绝。 `[Req-ID]: REQ-FTG-011, REQ-ESR-001~003`
- [x] 13.3 运行显式 retry、拆分、并发/事务、协调器恢复聚焦测试，证明不重跑 COMPLETED/SPLIT、不新增 task、不重新解析材料。 `[Req-ID]: REQ-FTG-010, REQ-FTG-011`
- [x] 13.4 运行 OpenSpec strict、diff/sensitive、构建与部署门禁；部署后只读确认原 task 可恢复，才允许发送一次同 task 官方 retry。 `[Req-ID]: REQ-FTG-011`

## 14. 功能清单提取 response-too-large 原地二分

- [x] 14.1 增加协调器 RED：`extract_function_list` 连续窗口收到 `response_too_large` 时只执行两个确定性连续子窗口；证明原样 unit 字段、重启跳过父/已完成子项、单 unit 失败关闭且 `structured_output_invalid` 不触发拆分。 `[Req-ID]: REQ-FTG-012`
- [x] 14.2 增加 MySQL RED：父 attempt、父 `SPLIT` 和两个功能清单子 work 原子提交；验证 operation/Skill/冻结闭包、回滚、并发唯一、父项零业务行以及既有 accepted hash/业务行不变。 `[Req-ID]: REQ-FTG-012`
- [x] 14.3 复用耐久拆分事务并接入 extraction coordinator；父/子稳定 identity 继续由完整冻结输入计算，恢复不得重新读取或解析材料。 `[Req-ID]: REQ-FTG-012`
- [x] 14.4 运行协调器、store、retry/heartbeat/recovery、MySQL/Flyway 相关回归以及 OpenSpec strict、diff/sensitive、构建门禁；KEE 将 `extract_function_list` 的 length 稳定映射为 `response_too_large` 前不得恢复真实任务。 `[Req-ID]: REQ-FTG-012`

## 15. 语义目标窗口与耐久上下文

- [x] 15.1 增加规划器/DTO RED：8~16 正常目标、短尾、章节/表格/功能路径边界、相邻不重叠 context、总数不超过32及 target-only evidence。 `[Req-ID]: REQ-FTG-013`
- [x] 15.2 新增 V17 RED/迁移：持久化 material document、context keys、parent lineage/depth；事务内验证同一 inventory、目标闭包和上下文相邻性，旧 NULL 记录兼容。 `[Req-ID]: REQ-FTG-013`
- [x] 15.3 接入 review/extraction coordinator：首次调用前注册计划，恢复优先读取耐久根/子窗口，完成兄弟不重跑；functional testcase 调用粒度保持不变。 `[Req-ID]: REQ-FTG-013`
- [x] 15.4 容量拆分 RED→GREEN：只二分目标并从 inventory 重算左右 context，父/子 lineage 原子提交、并发单赢家、普通错误/单目标失败关闭。 `[Req-ID]: REQ-FTG-013`
- [x] 15.5 运行 DTO/Wire、规划器、协调器、真实 MySQL/Flyway、retry/lease/recovery 和旧任务兼容回归；证明 context 不进入正式业务行。 `[Req-ID]: REQ-FTG-013`
- [x] 15.6 运行 OpenSpec strict、`git diff --check`、敏感新增行检查和构建；只报告仍待 KEE 部署及真实同任务验收，不部署、不发送业务 POST。 `[Req-ID]: REQ-FTG-013`

## 16. 功能清单目标原文闭包

- [x] 16.1 增加 DTO/validator RED：`target_quote` 必填且最多 512 code points；上下文冒充、未引用目标原文、伪造 evidence、最终 path 叶子不在 quote 中均整批拒绝；合法目标连续原文及布局空白正控通过。 `[Req-ID]: REQ-FTG-014`
- [x] 16.2 将 coordinator 的目标 unit 正文闭包传给 validator，context 不进入；mapper/validated row 保留 quote，正式 evidence 仍只取目标 keys。 `[Req-ID]: REQ-FTG-014`
- [x] 16.3 新增 V18 及 MySQL RED：任务级稳定功能项原子合并 quote，重启恢复相同列表，历史 NULL 记录兼容，失败批次 function item/binding 零写入。 `[Req-ID]: REQ-FTG-014`
- [x] 16.4 运行 extraction DTO/Wire/validator/coordinator、MySQL/Flyway、重启/并发/旧任务回归以及 strict/diff/sensitive/build 门禁；KEE 部署前不得业务 POST。 `[Req-ID]: REQ-FTG-014`

## 17. 逐材料调用的单文档授权

- [x] 17.1 增加协调器 RED：完整任务范围包含多文档时，功能清单提取旧实现错误发送全部范围；需求评审逐份调用、持久窗口恢复与已完成兄弟跳过均锁定为当前材料单文档授权。 `[Req-ID]: REQ-FTG-015`
- [x] 17.2 最小修改 extraction 公开编排接缝，复用 `singleDocumentAuthorization(material.documentId())`；不得改变任务快照、材料 inventory、reconcile/testcase 全量范围或 `material_key`。 `[Req-ID]: REQ-FTG-015`
- [x] 17.3 运行协调器聚焦、MySQL 事务/显式恢复/拆分/租约相关回归、必要普通路径测试、OpenSpec strict、diff/sensitive 与构建门禁，并完成 hunk 级复审。 `[Req-ID]: REQ-FTG-015`
- [x] 17.4 构建不可变验收 JAR，精确替换唯一 8082；只读确认运行构件、API、Flyway、任务与八类业务表不变后，只执行一次零业务写入重放，不调用官方 retry。 `[Req-ID]: REQ-FTG-015`

## 18. 历史功能清单大窗口调用前迁移

- [x] 18.1 增加协调器 RED：历史 1..32 窗口必须在任何 KEE 调用前形成 1..16/17..32，32 个目标单元全部且仅归属一次；完成兄弟和新语义窗口保持不变。 `[Req-ID]: REQ-FTG-016`
- [x] 18.2 增加真实 MySQL RED：排队父项、两个稳定子项和完整 evidence 分区原子发布；覆盖并发单赢家、第二子项失败回滚、accepted hash/八类部分业务行拒绝。 `[Req-ID]: REQ-FTG-016`
- [x] 18.3 最小实现历史 extraction 预拆分事务与协调器接缝；不新增迁移、不改父 attempt、不按 `model_execution_failed` 推断容量、不扩张其他 operation。 `[Req-ID]: REQ-FTG-016`
- [x] 18.4 运行协调器、MySQL 事务/并发、显式恢复、租约和旧记录兼容回归，以及 OpenSpec strict、diff/sensitive、构建门禁。 `[Req-ID]: REQ-FTG-016`
- [x] 18.5 构建新不可变 JAR并替换唯一 8082；只读证明 task、attempt、artifact 和八类业务表未变化后，只报告可以进行一次零写入小窗口重放，不实际发送重放或官方 retry。 `[Req-ID]: REQ-FTG-016`
