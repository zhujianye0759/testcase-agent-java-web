## Context

`FunctionalTestcaseResultValidator` 是结构化用例进入 `StructuredGenerationAcceptanceStore` 事务前的公共业务验收接缝。它当前验证目标回显、键引用闭包、状态、缺失信息和步骤编号，但只对 title、preconditions、action、expected 做内部键/占位符安全检查；`WorkItem` 也只携带事实键和证据键，不携带这些键对应的正式正文。

受控任务 `a422272c-a993-4553-8c46-58a89e39c20b` 证明合法引用键不能证明正文有依据：绑定事实和 parsed-unit 原文只明确“账号、正确密码、进入首页、显示用户名称”，已接收的 formal 用例却增加用户名/手机号/邮箱、Token/Session、登录接口和受保护资源。API 详情与 XLSX 一致，说明越界内容已在验收后持久化，不是导出层生成。

KEE 已加强直接依据的 Skill 约束，但运行失败证明现有 `functional-testcase-design` 输入只有 fact/evidence key 和测试点描述，没有把已验收正式事实正文传给模型。Java 仍拥有最终业务规则校验和入库责任，不能把提示词遵循当作信任边界；同时 KEE 也不能执行“直接复用来源片段”的合同，除非调用方提供当前授权正文。

最终任务 `cef21af2-20c6-446a-950b-c464dc17e951` 进一步证明支持闭包的上游也必须校验：已接收 `fact-00b8...` 虽引用合法 evidence，却把“正确密码”删写成“密码”，把原句拆写为“密码必须正确/用户状态必须正常/用户必须已注册”，并新增“会话状态由未登录变为已登录”。这些 fact 字段随后会被 Java 当作可信 `formal_supports`，因此只在 testcase 结果端校验不足以建立真实来源边界。

## Goals / Non-Goals

**Goals:**

- 在 Java 原子接收前，对 formal 用例的每个读者字段执行确定性、失败关闭的直接依据校验。
- 从持久化正式事实和其绑定 parsed-unit 恢复支持闭包，使进程重启后使用同一依据。
- 保持 pending_confirmation 的缺失信息和覆盖语义不变。
- 用现场事实与越界正文形成独立、快速、可重复的 RED。

**Non-Goals:**

- 不使用全局业务关键词黑名单，也不新增模型、中文分词器或外部依赖。
- 不让 Java 自动改写 formal 为 pending，不自动补 missing_information。
- 除新增 `functional-testcase-design.input.formal_supports` 外，不修改 KEE 顶层请求、其他 Skill 输入或任何结果字段；不修改既有任务业务数据，也不为复现创建第二个任务。
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

早期四字段接缝曾允许移除“验证/确认/正常/前提/操作/预期”等结构前缀；2026-08-22 冻结高粒度合同明确取代该阶段性豁免。最终 formal reader field 不再剥离任意前缀，只能等于一个完整允许来源值、一个完整 evidence text，或 decision 9 列出的五条固定中文通用语句。不会维护“用户名、手机号、Token”等禁词表；同一文字如果在相应正式来源中明确出现即可通过。

该规则有意保守：Java 不证明同义改写或隐含推理。无法按直接片段确定的有价值内容必须由模型返回为 `pending_confirmation` 并写明缺失信息，不能算 formal 覆盖。

### 4. 不新增 Java 业务错误的隐式模型重试

grounding 失败沿用 `business_validation_failed`，结果零接收。当前结构化 coordinator 只对 `model_unavailable` 和 `model_execution_failed` 做一次 Java 级重试；KEE 的一次 repair 也只处理结构错误。由于冻结请求没有安全的字段级纠正反馈入口，本 change 不把业务失败加入自动重试白名单，避免无反馈地额外调用模型。

KEE 已在现有 Skill 内加强直接依据约束，模型可以在首次结果中把无依据候选标为 pending_confirmation。若未来要求 Java 将精确失败位置反馈给模型，需要另行冻结不含正文泄漏的纠正合同，不能在本 change 猜字段。

### 5. H 列“33”不属于 Java 导出缺陷

同一 artifact 的 OfficeCLI 读取显示 H2:H9 均为空。原始 OOXML 的 `<v>33</v>` 是 shared-string 索引 33，解析后对应空字符串；H 列宽为 35。artifact-tool 把共享字符串索引当作值渲染，Java exporter 不作修改。

### 6. 将正式支持正文作为独立、最小的 KEE 业务输入

`FunctionalTestcaseDesignInput` 增加 `formal_supports` 数组，并定义独立不可变 input `FormalSupport`。每项严格包含 `fact_key`、`function`、`roles`、`trigger_conditions`、`inputs`、`business_rules`、`outputs`、`permissions`、`state_changes`、`exception_handling`、`external_dependencies`、support 级 `evidence_keys` 和 `evidence_texts`；不得混入 validator、数据库或投影字段。该字段清单由后续冻结合同补充 support 级 evidence identity，取代早期仅传正文的阶段性形状。

coordinator 只从同一 task 已验收的 `AcceptedFact` 构造该数组。支持项按当前 `test_point.requirement_fact_keys` 的确定顺序选择；`evidence_texts` 只包含该事实已绑定且同时属于当前 `test_point.evidence_keys` 闭包的 parsed-unit 正文，按冻结 evidence key 顺序去重。缺失 fact、空 formal supports、越界 evidence key 或正文缺失均在调用 KEE 前失败关闭。`testPoint.description` 只描述测试点，不是正式正文来源。

KEE 与 Java 使用同一类别映射：title 只从 `function` 复制；preconditions 只从 `roles`、`trigger_conditions`、`business_rules`、`permissions`、`state_changes` 复制；action 只从 `trigger_conditions`、`inputs` 复制；expected 只从 `outputs`、`state_changes`、`exception_handling`、`external_dependencies` 复制。任一读者字段也可逐字复制一个 bound `evidence_texts` 中完整出现的片段，但不得拼接或跨类别搬入新增业务含义。

该字段只帮助 KEE 首次生成时执行 direct-copy 合同。Java 仍按现有 `FunctionalTestcaseResultValidator` 对 title、precondition、action 和 expected 逐字段失败关闭；不得自动降级 formal、不得把 `business_validation_failed` 加入模型重试白名单，也不得把原始模型响应持久化。

### 7. requirement fact 在成为支持闭包前必须先由 cited evidence 逐字段证明

`RequirementMaterialReviewValidator` 仍是 review result 进入 `StructuredGenerationAcceptanceStore.acceptReview` 的业务验收入口，但其 `WorkItem` 必须获得当前切片中每个 allowed evidence key 对应的冻结 parsed-unit 正文。正文只能由 coordinator 当前 `RequirementMaterialQualityReviewInput.units` 或 store 按同 task/material/slice 耐久坐标解析，调用方提供的 key 不能单独充当授权事实。

对 fact 的 `function` 以及 `roles`、`trigger_conditions`、`inputs`、`business_rules`、`outputs`、`permissions`、`state_changes`、`exception_handling`、`external_dependencies` 中每个非空项，Java 执行与 testcase grounding 相同的 NFKC、大小写及空白/标点归一化，然后要求完整候选片段连续包含于该 fact 引用的至少一条 evidence 正文。不得接受同义改写、语义拆写或跨证据拼接。任一项不满足时，validator 在任何 fact/finding/reference 行写入前拒绝整个 review result；coordinator 记录稳定 `business_validation_failed`，不进入模型瞬时错误重试。

正向控制采用同一受控任务的 `fact-9439...`：其 function、role、trigger、input、整句 rule、output、permission 和 state 都能在 cited parsed-unit 中按上述归一化规则连续找到。该规则不改变 testcase validator，也不要求修改 KEE 结果字段。

### 8. 冻结一个高粒度用例接口，不建立第二套投影

`FunctionalTestcaseDesignInput` 增加可选 `authoring_information`；每个 `FormalSupport` 增加非空 `evidence_keys`。支持项 fact key 与测试点 requirement-fact key 集合闭合，每项 evidence key 属于测试点证据闭包，所有支持项 evidence key 并集精确等于测试点 evidence key 集合。evidence key 与 evidence text 必须按当前测试点 evidence key 顺序成对恢复；两个不同 key 即使正文完全相同也不得按正文去重或丢失映射。general-experience 必须显式发送空 `formal_supports`，formal_requirement 必须发送非空完整闭包。

`FunctionalTestcaseDesignResult.Testcase` 在既有键和状态字段上原位增加 `name`、`priority`、四类 `initialization` 数组、类型化 `inputs`、增强 `steps`、`expected_results`、两类评价标准、`termination_conditions`、`result_collection` 和 `authoring_information`。function path 继续由 Java 已确认功能映射派生，结果中不接受模型生成的 path。缺少来源的数组保持空；请求提供 author/date 时结果必须精确回显，未提供时两者必须为空字符串。

输入枚举、优先级、显式空数组、step 1 起连续编号和 exact JSON 字段由严格 DTO/Wire 测试锁定。Java 不自动填充缺失字段、不裁剪数组、不接受部分结果。

### 9. 正式读者字段采用完整值相等，不复用 fact 的连续子串规则

requirement fact 和坏例 quote 是“较大 parsed-unit 中的连续片段”，因此使用 NFKC、大小写和空白规范化后的连续包含，同时保留标点边界。正式 testcase 的 reader field 则必须等于一个完整的、按类别允许的 support 值或完整 evidence_text；不得把“账号登录成功”缩成“登录”，不得跨类别搬运或拼接。

只有下列五条不含业务含义的固定中文语句可以脱离业务来源出现：`实际结果满足本步骤预期结果。`、`系统服务终止，或执行过程中无法执行下一步操作。`、`记录实际结果、提示信息及必要证据。`、`满足前提和约束且未触发终止条件，逐步执行并记录结果。`、`全部预期结果满足则通过，任一不满足则不通过。`。除此之外，初始化、输入、步骤评价/终止/采集、总体预期、总体评价和终止条件中的业务内容必须完整来自当前 testcase 选择的 support/evidence 闭包。

### 10. 评审 finding 是只读评审事实，不能升级为正式依据

每个 finding 增加冻结的 13 类 `root_cause_kind`、`affected_scope`、`bad_source_example`、`proposed_good_example`。同一有界响应内 root cause 重复即整结果拒绝；影响单元必须是 finding evidence 子集，坏例 quote 必须连续存在于指定 parsed-unit，建议好例状态只能为 `pending_confirmation` 且中文文本必须包含“待需求方确认”。issue type、description、影响范围 summary、测试设计影响和两个 recommendation 均必须含中文分析；英文只允许真实坏例原文或明确标注的来源引用。

task 级存储以 `(task_id, root_cause_kind)` 作为根因聚合身份。跨有界调用的相同根因在事务内合并 evidence/affected scope，保留至少一个真实坏例和一个待确认好例；不同 root cause 绝不合并。合并后的 proposed good example、recommendation 和 bad example 只进入评审投影，不能进入 accepted fact、test point、testcase 或正式覆盖。

### 11. 持久化、详情和 Excel 使用同一验收记录

在现有 V12 之后使用首个空闲 Flyway 版本，仅增加表、可空列或 JSON 列与唯一索引，不修改已应用迁移或历史数据。validator 对完整 result 全部通过后才进入一个事务写入 testcase/finding 及引用；任一字段、引用、合并冲突或数据库失败均零部分接收。重启恢复只读取已验收持久化字段，不能依赖内存 registry 或刚收到的模型对象。

任务详情 API 和 `StructuredWorkbookExporter` 读取同一持久化投影。页面使用现有详情页 token/组件显示初始化、输入、逐步动作/预期/评价/异常/采集、总体预期、前提、终止、两类评价、作者日期、formal/pending 与中文来源说明。Excel 仍恰好两个 Sheet：`需求与功能清单审查发现` 和 `测试用例`；评审按根因一行，高粒度用例字段以读者可执行的中文多行文本导出。两处均不得展示 raw JSON/Markdown、内部枚举、unit/evidence/fact/case 等机器键或凭据，并继续执行公式注入防护。

## Risks / Trade-offs

- **合法同义改写被拒绝** → 这是失败关闭的预期代价；模型可保留为待确认候选，正式内容使用来源直接片段。
- **固定包装规则逐渐变成业务词表** → 包装只允许无业务含义的结构词，并用正向/对抗测试锁定；不得添加领域名词。
- **只恢复事实键而丢失正文** → MySQL 恢复测试覆盖首次执行和重启路径，要求支持闭包内容一致。
- **部分用例写入后才发现越界** → validator 必须在事务行写入前验证完整 Result；集成测试断言失败后 test point、case、step 和 binding 均为零新增。
- **合法 review fact 被保守拒绝** → 正式 fact 只接受可直接审计的原文片段；同义改写由 KEE 在返回前纠正，Java 不做语义猜测。

## Migration Plan

先加入 DTO/validator RED，再新增 V12 之后首个空闲的增量 Flyway 迁移，扩充原子接收和耐久恢复，最后接入详情与双 Sheet Excel。迁移不得修改已应用 V10/V11/V12、不得人工修改 Flyway history、不得重写既有完成任务或制品。通过聚焦与 MySQL/Testcontainers、页面/Excel、旧路径和 OpenSpec 门禁后，才从当前干净 worktree 构建并替换 Java 后端与前端；KEE 的构建和部署由 KEE 主任务负责。

## Open Questions

- 若产品未来要求接受同义改写并仍能逐字段审计，需要 KEE 返回字段级 grounding（field path、fact/evidence key、source quote/span）并由 Java 验证；该能力不在当前冻结合同内。
