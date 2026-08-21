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
