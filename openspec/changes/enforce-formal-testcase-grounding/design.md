## Context

`FunctionalTestcaseResultValidator` 是结构化用例进入 `StructuredGenerationAcceptanceStore` 事务前的公共业务验收接缝。它当前验证目标回显、键引用闭包、状态、缺失信息和步骤编号，但只对 title、preconditions、action、expected 做内部键/占位符安全检查；`WorkItem` 也只携带事实键和证据键，不携带这些键对应的正式正文。

受控任务 `a422272c-a993-4553-8c46-58a89e39c20b` 证明合法引用键不能证明正文有依据：绑定事实和 parsed-unit 原文只明确“账号、正确密码、进入首页、显示用户名称”，已接收的 formal 用例却增加用户名/手机号/邮箱、Token/Session、登录接口和受保护资源。API 详情与 XLSX 一致，说明越界内容已在验收后持久化，不是导出层生成。

KEE 已加强直接依据的 Skill 约束，但未改变 JSON 字段。Java 仍拥有最终业务规则校验和入库责任，不能把提示词遵循当作信任边界。

## Goals / Non-Goals

**Goals:**

- 在 Java 原子接收前，对 formal 用例的每个读者字段执行确定性、失败关闭的直接依据校验。
- 从持久化正式事实和其绑定 parsed-unit 恢复支持闭包，使进程重启后使用同一依据。
- 保持 pending_confirmation 的缺失信息和覆盖语义不变。
- 用现场事实与越界正文形成独立、快速、可重复的 RED。

**Non-Goals:**

- 不使用全局业务关键词黑名单，也不新增模型、中文分词器或外部依赖。
- 不让 Java 自动改写 formal 为 pending，不自动补 missing_information。
- 不修改 KEE 请求/返回字段，不修改既有任务业务数据，也不为复现创建第二个任务。
- 不在 Excel 或页面末端清洗已经错误接收的业务内容。

## Decisions

### 1. 只在正式用例业务验收接缝判定

`FunctionalTestcaseResultValidator.validate(WorkItem, Result)` 保持唯一判定入口。`StructuredGenerationAcceptanceStore.acceptTestcases` 先调用它，只有完整结果通过后才进入事务写入。这样一个 formal 字段越界会让整个 Skill 结果零接收；持久化、详情和 Excel 无需建立第二套规则。

替代方案是在 repository 或 exporter 过滤文本。该方案会保留错误覆盖状态并造成页面/Excel不一致，因此拒绝。

### 2. Java 恢复不可变、按键绑定的正式支持闭包

Java 内部 `WorkItem` 增加按 fact key 绑定的正式支持对象，包含完整 requirement fact 字段以及该 fact 当前 evidence key 对应的持久化 parsed-unit 正文。support key 集合必须与测试点冻结的 requirement-fact/evidence 闭包一致；恢复查询必须限定同一 task，不能由调用方扩大。

现有 KEE DTO 已提供完整 requirement fact 字段，`material_inventory_unit` 已持久化证据正文，因此不需要新增 JSON 字段。当前 `AcceptedFact` 恢复投影遗漏 roles、triggerConditions、outputs，Java 需要补齐这些已落库字段。

### 3. 使用保守的直接片段证明，不做语义猜测

对每条 formal 用例分别检查 title、每条 precondition、每个 step.action 和 step.expected。校验先执行 Unicode NFKC、ASCII 大小写和空白/标点规范化，再按字段语义只接受绑定支持闭包中的直接片段：标题对应 function；前提对应 roles、trigger conditions、business rules、permissions 和状态前置；动作对应 trigger conditions 与 inputs；预期对应 outputs、state changes、exception handling 和 dependencies。绑定 parsed-unit 只可证明候选完整业务片段确实原样出现，不能用零散字符拼接新断言。

允许的固定包装仅限不携带业务含义、按字段定义的前缀：title 为“验证”“确认”“正常”，precondition 为“前提：”“前置条件：”，action 为“操作：”“执行：”，expected 为“预期：”“预期结果：”。除 title 可连续移除多个既有结构前缀外，其余字段最多移除一个对应前缀。删除包装后，剩余完整业务片段必须由一个允许来源完整覆盖，不能用零散词语拼接新断言。不会维护“用户名、手机号、Token”等禁词表；同一文字如果在相应正式来源中明确出现即可通过。

该规则有意保守：Java 不证明同义改写或隐含推理。无法按直接片段确定的有价值内容必须由模型返回为 `pending_confirmation` 并写明缺失信息，不能算 formal 覆盖。

### 4. 不新增 Java 业务错误的隐式模型重试

grounding 失败沿用 `business_validation_failed`，结果零接收。当前结构化 coordinator 只对 `model_unavailable` 和 `model_execution_failed` 做一次 Java 级重试；KEE 的一次 repair 也只处理结构错误。由于冻结请求没有安全的字段级纠正反馈入口，本 change 不把业务失败加入自动重试白名单，避免无反馈地额外调用模型。

KEE 已在现有 Skill 内加强直接依据约束，模型可以在首次结果中把无依据候选标为 pending_confirmation。若未来要求 Java 将精确失败位置反馈给模型，需要另行冻结不含正文泄漏的纠正合同，不能在本 change 猜字段。

### 5. H 列“33”不属于 Java 导出缺陷

同一 artifact 的 OfficeCLI 读取显示 H2:H9 均为空。原始 OOXML 的 `<v>33</v>` 是 shared-string 索引 33，解析后对应空字符串；H 列宽为 35。artifact-tool 把共享字符串索引当作值渲染，Java exporter 不作修改。

## Risks / Trade-offs

- **合法同义改写被拒绝** → 这是失败关闭的预期代价；模型可保留为待确认候选，正式内容使用来源直接片段。
- **固定包装规则逐渐变成业务词表** → 包装只允许无业务含义的结构词，并用正向/对抗测试锁定；不得添加领域名词。
- **只恢复事实键而丢失正文** → MySQL 恢复测试覆盖首次执行和重启路径，要求支持闭包内容一致。
- **部分用例写入后才发现越界** → validator 必须在事务行写入前验证完整 Result；集成测试断言失败后 test point、case、step 和 binding 均为零新增。

## Migration Plan

无需数据库迁移。先加入 RED，再扩充 Java 内部恢复投影和 validator；通过聚焦与 MySQL/Testcontainers 回归后才能提交。既有完成任务和制品不重写，新规则只约束后续接收。

## Open Questions

- 若产品未来要求接受同义改写并仍能逐字段审计，需要 KEE 返回字段级 grounding（field path、fact/evidence key、source quote/span）并由 Java 验证；该能力不在当前冻结合同内。
