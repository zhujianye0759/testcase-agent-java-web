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

对 fact 的 `function` 以及 `roles`、`trigger_conditions`、`inputs`、`business_rules`、`outputs`、`permissions`、`state_changes`、`exception_handling`、`external_dependencies` 中每个非空项，Java 先执行 NFKC 和 `Locale.ROOT` 小写，再移除所有 `Character.isWhitespace` 或 `Character.isSpaceChar` 识别的 Unicode 空白；标点和其他字符保持不变。该规则只消除 PDF 排版换行、全角空格和普通空格造成的断词，不允许删标点、同义改写、语义拆写或跨 evidence 拼接。完整候选片段仍必须连续包含于该 fact 引用的某一条 evidence 正文。任一项不满足时，validator 在任何 fact/finding/reference 行写入前拒绝整个 review result；coordinator 记录稳定 `business_validation_failed`，不进入模型瞬时错误重试。

坏例 `bad_source_example.quote` 使用独立 normalizer，继续执行 NFKC、`Locale.ROOT` 小写、默认正则 `\s+` 压缩为一个普通空格并 `strip`。因此坏例 quote 不得通过删除原文内部空格获得匹配；fact 的排版容错不会放宽坏例引用边界。

正向控制采用同一受控任务的 `fact-9439...`：其 function、role、trigger、input、整句 rule、output、permission 和 state 都能在 cited parsed-unit 中按上述归一化规则连续找到。该规则不改变 testcase validator，也不要求修改 KEE 结果字段。

### 8. 冻结一个高粒度用例接口，不建立第二套投影

`FunctionalTestcaseDesignInput` 增加可选 `authoring_information`；每个 `FormalSupport` 增加非空 `evidence_keys`。支持项 fact key 与测试点 requirement-fact key 集合闭合，每项 evidence key 属于测试点证据闭包，所有支持项 evidence key 并集精确等于测试点 evidence key 集合。evidence key 与 evidence text 必须按当前测试点 evidence key 顺序成对恢复；两个不同 key 即使正文完全相同也不得按正文去重或丢失映射。general-experience 必须显式发送空 `formal_supports`，formal_requirement 必须发送非空完整闭包。

`FunctionalTestcaseDesignResult.Testcase` 在既有键和状态字段上原位增加 `name`、`priority`、四类 `initialization` 数组、类型化 `inputs`、增强 `steps`、`expected_results`、两类评价标准、`termination_conditions`、`result_collection` 和 `authoring_information`。function path 继续由 Java 已确认功能映射派生，结果中不接受模型生成的 path。缺少来源的数组保持空；请求提供 author/date 时结果必须精确回显，未提供时两者必须为空字符串。

输入枚举、优先级、显式空数组、step 1 起连续编号和 exact JSON 字段由严格 DTO/Wire 测试锁定。Java 不自动填充缺失字段、不裁剪数组、不接受部分结果。

### 9. 正式读者字段采用完整值相等，不复用 fact 的连续子串规则

requirement fact 和坏例 quote 都是“较大 parsed-unit 中的连续片段”，但使用 decision 7 明确的两个独立 normalizer：fact 忽略 Unicode 布局空白，坏例 quote 保留压缩后的内部空白；两者都保留标点边界。正式 testcase 的 reader field 则必须等于一个完整的、按类别允许的 support 值或完整 evidence_text；不得把“账号登录成功”缩成“登录”，不得跨类别搬运或拼接。

只有下列五条不含业务含义的固定中文语句可以脱离业务来源出现：`实际结果满足本步骤预期结果。`、`系统服务终止，或执行过程中无法执行下一步操作。`、`记录实际结果、提示信息及必要证据。`、`满足前提和约束且未触发终止条件，逐步执行并记录结果。`、`全部预期结果满足则通过，任一不满足则不通过。`。除此之外，初始化、输入、步骤评价/终止/采集、总体预期、总体评价和终止条件中的业务内容必须完整来自当前 testcase 选择的 support/evidence 闭包。

### 10. 评审 finding 是只读评审事实，不能升级为正式依据

每个 finding 增加冻结的 13 类 `root_cause_kind`、`affected_scope`、`bad_source_example`、`proposed_good_example`。同一有界响应内 root cause 重复即整结果拒绝；影响单元必须是 finding evidence 子集，坏例 quote 必须连续存在于指定 parsed-unit，建议好例状态只能为 `pending_confirmation` 且中文文本必须包含“待需求方确认”。issue type、description、影响范围 summary、测试设计影响和两个 recommendation 均必须含中文分析；英文只允许真实坏例原文或明确标注的来源引用。

task 级存储以 `(task_id, root_cause_kind)` 作为根因聚合身份。跨有界调用的相同根因在事务内合并 evidence/affected scope，保留至少一个真实坏例和一个待确认好例；不同 root cause 绝不合并。合并后的 proposed good example、recommendation 和 bad example 只进入评审投影，不能进入 accepted fact、test point、testcase 或正式覆盖。

### 11. 持久化、详情和 Excel 使用同一验收记录

在现有 V12 之后使用首个空闲 Flyway 版本，仅增加表、可空列或 JSON 列与唯一索引，不修改已应用迁移或历史数据。validator 对完整 result 全部通过后才进入一个事务写入 testcase/finding 及引用；任一字段、引用、合并冲突或数据库失败均零部分接收。重启恢复只读取已验收持久化字段，不能依赖内存 registry 或刚收到的模型对象。

任务详情 API 和 `StructuredWorkbookExporter` 读取同一持久化投影。页面使用现有详情页 token/组件显示初始化、输入、逐步动作/预期/评价/异常/采集、总体预期、前提、终止、两类评价、作者日期、formal/pending 与中文来源说明。Excel 仍恰好两个 Sheet：`需求与功能清单审查发现` 和 `测试用例`；评审按根因一行，高粒度用例字段以读者可执行的中文多行文本导出。两处均不得展示 raw JSON/Markdown、内部枚举、unit/evidence/fact/case 等机器键或凭据，并继续执行公式注入防护。

### 12. 材料评审容量失败以 work 树原地二分，不重放冻结来源

KEE 将 `requirement-material-quality-review` 的模型长度终止收敛为 `response_too_large`。Java 不把它当成同一 32-unit 请求的瞬时模型重试，而是把当前连续窗口按 `mid = size / 2` 确定性划分为左、右两个非空子窗口。子输入继续使用现有 `RequirementMaterialQualityReviewInput` 和 `WorkRegistration`；稳定 identity 仍由 task、operation 和完整子输入序列化后使用 length-prefixed SHA-256 计算，因此同一冻结 inventory 在重启后得到相同身份。

`structured_generation_work_item` 增加 `SPLIT` 终态。`StructuredGenerationAcceptanceStore.splitReviewWork` 在锁定当前父 work 和 attempt 后验证：Skill/operation 为材料评审；父 work 尚无 accepted hash 和业务行；两个子 work 与父 task/material/source 一致；ordinal 首尾连续；证据列表非空且按左右顺序精确拼回父闭包。随后同一事务把 attempt 记为 `FAILED/response_too_large`、父 work 改为 `SPLIT` 并注册两个 `QUEUED` 子 work。并发重复拆分或租约已失效均失败关闭。

协调器执行材料评审时先注册当前窗口：`COMPLETED` 直接跳过，`SPLIT` 则只递归相同两个子窗口，其他状态才领取和调用 KEE。父项拆分成功后关闭当前租约，再从左右子窗口继续。1-unit 窗口没有合法二分，沿用现有 fail-closed 路径。阶段聚合和“材料阶段已完成”查询只计算非 `SPLIT` 叶子；父项只保留审计和恢复身份。

重试/恢复时优先读取 `GenerationTaskRepository` 已保存且与冻结 scope 精确一致的完整 material inventory，不重新调用 parsed-units。该变化只用于已有完整 inventory 的结构化任务恢复；首次任务仍通过受支持的 parsed-units API 建立 inventory，显式材料替换流程保持不变。

### 13. 显式恢复只重置唯一失败叶子，保留排队兄弟

递归二分可能在左侧最小叶子失败时留下多个尚未执行的 `QUEUED` 兄弟叶子。`structuredRetryEligibility` 不能再把“所有非 COMPLETED/SPLIT work 必须恰好一条”作为安全条件；该条件会让一个零写入、可审计的失败叶子无法从同一任务恢复。

显式恢复仍锁定 task 行和全部未完成 work 行，只允许以下唯一形状：恰好一个 `FAILED` work、零个 `RUNNING` work，其余未完成 work 全部为 `QUEUED`；这些 work 的 accepted hash 必须全部为空，且不得拥有 fact、finding、function item、reconciliation、test point、test case、step 或 binding。最新失败 attempt 仍必须命中既有显式重试白名单。事务只把唯一失败 work 改为 `QUEUED` 并清除其当前安全诊断，其他排队兄弟保持不变；任务恢复为 `QUEUED` 后由协调器按稳定 identity 跳过 `COMPLETED`/`SPLIT` 并顺序领取叶子。多个失败叶子、排队 work 部分写入或并发状态漂移继续失败关闭。

### 14. 功能清单提取复用同一耐久拆分协议

`feature-scope-reconciliation / extract_function_list` 的固定 32-unit 输入仍可能因一个切片包含大量功能行而达到模型输出上限。KEE 必须把该 operation 的 provider length 终止稳定映射为 `response_too_large`；Java 不得从 `structured_output_invalid`、日志文本或响应正文猜测容量原因。

Java 将 flat extraction loop 改为与材料评审相同的 split-aware 递归：当前冻结窗口先按完整输入计算稳定 identity 并注册；`COMPLETED` 直接跳过，`SPLIT` 只递归重建相同两个子窗口，其他状态才领取并调用 KEE。收到 `response_too_large` 且 unit 数大于一时，按 `mid = size / 2` 构造两个 `FunctionListExtractionInput`，原样保留 material key、source label、unit key、ordinal、content 和顺序。

store 复用一个带 operation 约束的私有拆分事务：现有 `splitReviewWork` 继续只接受材料评审，新 `splitFunctionListExtractionWork` 只接受功能清单提取；二者共享父 claim/租约、父 accepted/八类业务行、子坐标、连续 ordinal 和 evidence 拼接的锁内校验。父 attempt 记为 `FAILED/response_too_large`，父 work 记为 `SPLIT`，两个子 work 同事务注册。这样既不复制事务实现，也不会让其他 Skill 获得宽泛拆分权限。

1-unit extraction 没有合法子窗口，保持现有失败路径；`response_too_large` 不进入自动重试白名单。真实任务恢复前还须以已部署 KEE 证明公开错误类型已按上述合同返回，否则 Java 保持当前失败现场，不发送 retry。

### 15. 输出归属窗口与只读上下文分别冻结

`requirement-material-quality-review` 和 `extract_function_list` 的 `units` 改为当前调用负责产出结果的目标单元；可选 `context_units` 只携带同一材料中紧邻目标窗口、与目标不重叠的原样 parsed-unit。两组元组都保留 `unit_key/ordinal/content`，合计不超过 32。validator、namespace 和 acceptance store 的 allowed evidence 继续只使用 `units`，任何 `context_units` key 都不能进入 fact、finding、function item 或 binding。

`StructuredMaterialSlicePlanner` 先验证完整 inventory 的全局连续性和唯一性，再形成通常 8~16 个目标单元的窗口。规划器以 12 为无明显边界时的确定性中心，并在 8~16 范围内优先结束于章节标题、表格区段或功能路径区段边界；为避免人为制造 1~7 个单元的短尾，必要时调整前一窗口。材料总量本身不足 8 时允许一个短尾窗口。上下文从目标前后各取最近的有界单元，按全局 ordinal 排序；不得跳过更近单元去选择更远上下文。

V17 仅向 `structured_generation_work_item` 增加可空 `material_document_id`、可空 `context_evidence_keys_json`、可空 `parent_work_item_id` 和默认 0 的 `split_depth`，并增加父项索引/外键。新 work 在首次远程调用前持久化这些坐标；旧记录保持 NULL/0，并按已有目标 ordinal/evidence 恢复，不因新规划器重新分片。注册事务从同 task/document 的 `material_inventory_unit` 验证目标 key 与 ordinal 精确一致、context 属于同一材料且紧邻/不重叠。

协调器优先读取已注册根窗口：旧 work 使用其已保存目标和空 context；新 work 使用其已保存目标/context。父 work 为 `SPLIT` 时只读取其耐久子项，不从当前进程猜测 lineage。收到明确 `response_too_large` 时，规划器只二分目标 `units`，然后从完整 task-owned inventory 为左右子目标重新计算相邻 context；父 attempt、父 `SPLIT` 和带 `parent_work_item_id/split_depth` 的两个子 work 仍在一个事务发布。普通 `structured_output_invalid` 不拆分，单目标单元容量失败稳定终止。

KEE 结构化调用不再由 Java 维护或推断供应商输出 token 上限。Java 不查询公网模型能力，也不新增模型上限表或配置；容量恢复只依赖公开、稳定的 `response_too_large`。

### 16. 功能项必须携带目标单元中的直接原文

`extract_function_list.function_list_items[*]` 增加必填 `target_quote`，上限为 512 个 Unicode code points。DTO 负责严格字段和长度边界；`FunctionListExtractionValidator` 使用当前 work 已冻结的目标 `units` 构造 `evidence_key -> content` 映射，并要求 quote 在该项自身 `evidence_keys` 引用的至少一个目标单元正文中连续出现。比较执行 NFKC、对完整字符串执行 `Locale.ROOT` 小写并移除 Java `Character.isWhitespace || Character.isSpaceChar` 的精确 Unicode 集合，标点和其他字符保留，不允许跨 evidence 拼接或同义改写。Greek final sigma、U+001C/U+0085、全角分隔符和尾随空段夹具锁定 Java/KEE 跨语言一致性。

上下文正文不进入 validator 的目标正文映射。因此模型即使从 `context_units` 读到层级前缀，也必须至少引用一个由目标单元自身原文支持的功能项 quote。validator 还按冻结分隔符提取 `path` 最后非空叶子，并要求该叶子使用相同规范化后存在于 quote；context 只能补父级前缀，不能把目标中的“备注”等无关文本包装为一个功能项。伪造目标 evidence key、引用未选择目标单元的 quote、缺失/超长 quote、叶子不闭合或后续行失败都会在返回任何 `ValidatedItem` 前拒绝整批。

同一路径/说明可能跨目标窗口合并为同一个 Java 稳定功能键。V18 为任务级功能项增加可空 `target_quotes_json`：新接收行至少有一个已验证 quote，并在原有任务级行锁内确定性合并；历史行保持 NULL 并恢复为空列表。该字段只用于审计和重启恢复，不加入稳定 key，也不扩大 reconcile/testcase 的 wire shape。

### 17. 任务冻结范围与逐材料调用授权分离

任务的 `RequirementScope` 继续冻结用户选择的全部材料文档，材料清单、parsed-unit 遍历、工作项规划和后续任务级核对均以该完整快照为准。`requirement-material-quality-review` 与 `extract_function_list` 是逐材料调用；协调器在真正调用 KEE 前，必须使用当前耐久 `materialDocumentId` 从完整快照派生单文档授权。`material_key` 仍是调用方键，不承担文档授权身份。

首次执行、容量拆分子窗口、显式恢复和进程重启统一进入同一个窗口执行方法，因此必须从持久化 work 对应的材料身份派生相同的单文档授权。已完成兄弟继续跳过；收窄一次 HTTP 调用的 `knowledge_ids/system_scopes.knowledge_ids` 不得改变任务快照或减少其他材料的逐份遍历。

任务级 `reconcile_page` 与 `functional-testcase-design` 仍需要完整冻结材料范围，继续传递完整 `RequirementScope`。这避免把逐材料服务端来源校验错误扩散为核对或用例阶段的范围缩小。

### 18. 历史功能清单大窗口在联网前迁移为耐久子窗口

V17 之前的 `FEATURE_SCOPE_EXTRACT` work 没有 `material_document_id`、context 或 lineage，历史规划器会按最多 32 个目标单元恢复。当前新任务已经使用 8~16 个目标单元的语义窗口，因此本决策只处理显式恢复后的历史排队叶子，不改变新任务规划。

协调器注册历史窗口后先检查 `COMPLETED`/`SPLIT`。仅当该 work 仍未接受、目标单元数大于 16 且属于 `FEATURE_SCOPE_EXTRACT` 时，才在任何 claim、attempt 或 KEE 调用之前按确定性中点形成两个非空子输入。历史输入没有 context；子输入继续保留这个旧 wire identity，不为旧记录补造 context 或材料 lineage。32 个单元形成 16+16，31 个形成 15+16；目标键、ordinal、content 和顺序拼接必须精确等于父窗口。

`StructuredGenerationAcceptanceStore.splitQueuedHistoricalFunctionListExtractionWork` 在事务内锁定父 work，要求状态为 `QUEUED`、`material_document_id IS NULL`、accepted hash 为空、八类 work-owned 业务行均为零，并再次验证 Skill、operation、父子材料坐标和完整 evidence 分区。两个稳定 identity 子 work、父 `SPLIT` 状态全有或全无；父的历史失败 attempt 保留不变，不伪造新的容量错误。并发调用只有一个事务发布子项，另一个观察到已 `SPLIT` 后幂等返回。

该迁移不把 `model_execution_failed` 或其他错误解释为容量错误。失败 task 仍必须先通过既有显式恢复门禁把唯一零写入失败叶子恢复为 `QUEUED`；本事务不得绕过 task 状态机。`REQUIREMENT_MATERIAL_REVIEW`、其他 operation、单目标窗口和已完成兄弟均不进入该路径。重启继续从相同历史根 identity 观察 `SPLIT` 并只领取未完成子叶子，不重新解析材料。

## Risks / Trade-offs

- **合法同义改写被拒绝** → 这是失败关闭的预期代价；模型可保留为待确认候选，正式内容使用来源直接片段。
- **固定包装规则逐渐变成业务词表** → 包装只允许无业务含义的结构词，并用正向/对抗测试锁定；不得添加领域名词。
- **只恢复事实键而丢失正文** → MySQL 恢复测试覆盖首次执行和重启路径，要求支持闭包内容一致。
- **部分用例写入后才发现越界** → validator 必须在事务行写入前验证完整 Result；集成测试断言失败后 test point、case、step 和 binding 均为零新增。
- **合法 review fact 被保守拒绝** → 正式 fact 只接受可直接审计的原文片段；同义改写由 KEE 在返回前纠正，Java 不做语义猜测。
- **拆分父项被错误计为未完成或成功项** → 聚合、恢复和 retry 查询显式排除 `SPLIT` 父项，仅叶子 work 决定材料阶段终态。
- **拆分事务后进程崩溃导致重复父调用** → 父 `SPLIT` 与两个子 work 同事务提交，重启按冻结 input 计算同一子 identity，不依赖进程内 Map。
- **把任意功能清单结构错误误当容量错误** → extraction 只识别公开 `response_too_large`；`structured_output_invalid` 和其他错误不拆分，且 KEE length 映射部署是恢复前门禁。
- **新规划器让旧任务产生不同 identity** → 协调器优先恢复数据库中已有根窗口及其耐久子项；只有从未规划过该材料/operation 时才创建语义窗口。
- **context 被误当正式依据** → DTO 允许传输，但 work allowed-evidence、validator 和所有 binding 只接受目标 `units`；MySQL 测试同时断言 context key 不进入业务表。
- **模型把 context 文本冒充目标功能依据** → 每个功能项 quote 只在其引用目标 evidence 正文中逐单元校验；完整批次先验证后写入，失败时功能项和 binding 均为零。
- **逐材料调用误带全任务范围** → review/extraction 在公开编排接缝派生当前 `materialDocumentId` 的单文档授权；多材料和恢复测试同时证明完整任务快照与逐份遍历不变。
- **把上游网络 EOF 当容量错误** → 旧窗口预拆分只由持久窗口版本和目标数触发，发生在网络前；错误类型不参与判断，其他 operation 和新语义窗口不扩张。

## Migration Plan

先加入 DTO/validator RED，再新增 V12 之后首个空闲的增量 Flyway 迁移，扩充原子接收和耐久恢复，最后接入详情与双 Sheet Excel。迁移不得修改已应用 V10/V11/V12、不得人工修改 Flyway history、不得重写既有完成任务或制品。通过聚焦与 MySQL/Testcontainers、页面/Excel、旧路径和 OpenSpec 门禁后，才从当前干净 worktree 构建并替换 Java 后端与前端；KEE 的构建和部署由 KEE 主任务负责。

## Open Questions

- 若产品未来要求接受同义改写并仍能逐字段审计，需要 KEE 返回字段级 grounding（field path、fact/evidence key、source quote/span）并由 Java 验证；该能力不在当前冻结合同内。
