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
- [ ] 9.3 更新 Vue 详情页，复用现有 PC 详情模板和语义 token，覆盖 ready/empty/error、1440x820 与相关窄宽。 `[Req-ID]: REQ-FTG-009`

## 10. 验证、提交与部署

- [x] 10.1 运行 DTO/Wire/validator/coordinator/acceptance/export/detail 聚焦测试及 MySQL/Testcontainers/Flyway 回归。 `[Req-ID]: REQ-FTG-006~009`
- [x] 10.2 运行受影响后端回归、前端 unit/typecheck/lint/build、OpenSpec strict、git diff --check、敏感形态和主任务事务/权限/泄漏复审。 `[Req-ID]: all`
- [ ] 10.3 本地提交、不推送不归档；从干净 worktree 构建并替换 Java 8082 和 5173，保存页面证据。不得创建或重试最终业务任务。 `[Req-ID]: all`
