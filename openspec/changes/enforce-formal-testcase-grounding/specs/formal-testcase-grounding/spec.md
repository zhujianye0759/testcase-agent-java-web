## ADDED Requirements

### Requirement: [REQ-FTG-001] 正式用例读者字段必须有直接正式依据
Java SHALL 在原子持久化前，对 `case_status=formal` 的 title、每条 precondition、每个 step.action 和 step.expected 分别验证其业务内容由该用例绑定且属于当前测试点闭包的正式 requirement fact 或 evidence 正文直接支持。合法的 `requirement_fact_keys` 和 `evidence_keys` 只证明引用闭包，MUST NOT 单独证明读者正文有依据。校验 MUST NOT 使用全局业务关键词黑名单；同一术语在相应正式来源中明确出现时 SHALL 可被接受。

最终高粒度合同由 REQ-FTG-006 取代早期前缀包装豁免。Java MUST NOT 移除“验证/确认/正常/前提/操作/预期”等任意前缀后再判定来源；formal reader field 只能等于一个完整允许来源值、完整 evidence text 或冻结的五条通用中文语句，且 MUST NOT 把零散词语拼接为依据。

#### Scenario: 泛称账号不得扩成具体账号类型
- **WHEN** 绑定正式事实和证据只明确“账号”，formal 用例的 title、precondition、action 或 expected 增加“用户名”“手机号”或“邮箱”
- **THEN** Java SHALL 拒绝完整候选结果且零业务行接收

#### Scenario: 认证机制没有正式依据
- **WHEN** 绑定正式事实只描述登录状态变化，formal 用例增加 Token、Session、登录接口或受保护资源等未直接支持的机制
- **THEN** Java SHALL 拒绝完整候选结果且不得计入正式覆盖

#### Scenario: 相同术语已有正式依据
- **WHEN** formal 用例的业务片段在其绑定正式事实或 evidence 的相应正文中明确出现
- **THEN** Java SHALL 在其他业务规则也满足时允许该用例通过 grounding 校验

#### Scenario: 未冻结叙述包装不能绕过完整值规则
- **WHEN** formal 用例在已有直接依据前增加“验证”“前提”“操作”或“预期”等未列入五条通用语句的包装
- **THEN** Java SHALL 按最终完整值规则拒绝，不得先删除包装再放行

### Requirement: [REQ-FTG-002] 待确认候选与正式覆盖保持分离
没有直接正式依据但仍有测试价值的具体化内容 SHALL 仅以 `pending_confirmation` 接收，并 MUST 含至少一条非空 `missing_information`。Java MUST NOT 自动把 formal 改写为 pending、自动补缺失信息或让待确认候选贡献正式覆盖；formal 测试点仍 MUST 至少有一条通过 grounding 的 formal 用例才满足正式覆盖。

#### Scenario: 模型显式降级无依据候选
- **WHEN** 模型把无直接依据的具体化候选返回为 `pending_confirmation` 并说明待确认信息
- **THEN** Java SHALL 保留该候选但 SHALL NOT 将其计入正式覆盖

#### Scenario: 待确认候选没有缺失说明
- **WHEN** `pending_confirmation` 候选的 `missing_information` 为空或只有空白
- **THEN** Java SHALL 拒绝完整候选结果

### Requirement: [REQ-FTG-003] 支持闭包必须耐久恢复且投影不得绕过
Java SHALL 从同一 task 已验收的完整 requirement fact 字段和绑定 parsed-unit 正文恢复不可变支持闭包，并 SHALL 在当前测试点冻结的 fact/evidence key 范围内验证。进程重启后 MUST 得到相同判定。只有通过该校验的数据才可写入 test point、test case、step 和 reference binding，并由任务详情与双 Sheet Excel 读取；投影层 MUST NOT 单独清洗或补偿 grounding 失败。

#### Scenario: 重启后继续用已持久化事实判断
- **WHEN** 上游事实已完成、进程重启后继续尚未完成的用例工作
- **THEN** Java SHALL 从数据库恢复完整正式支持闭包并执行与首次运行相同的 grounding 校验

#### Scenario: 一个读者字段越界
- **WHEN** 同一 Skill 结果中任一 formal 用例的任一受检读者字段无法直接支持
- **THEN** Java SHALL 在写入前拒绝完整结果，任务详情和 Excel SHALL 不出现该结果的任何业务行

### Requirement: [REQ-FTG-004] KEE 用例设计输入必须携带当前正式支持正文
Java SHALL 在 `functional-testcase-design.input` 中发送 `formal_supports` 数组。每项 SHALL 精确且仅包含 `fact_key`、`function`、`roles`、`trigger_conditions`、`inputs`、`business_rules`、`outputs`、`permissions`、`state_changes`、`exception_handling`、`external_dependencies`、support 级 `evidence_keys` 和 `evidence_texts`。该字段只属于 Java→KEE 的业务输入，MUST NOT 改变普通 KEE Agent 请求。后续冻结合同增加的 support 级 evidence identity 与高粒度 result 由 REQ-FTG-006 约束。

Java SHALL 只从同一 task 已验收 `AcceptedFact` 及其绑定 parsed-unit 正文构造支持项，并 SHALL 只选择当前 `test_point.requirement_fact_keys` 和 `test_point.evidence_keys` 授权闭包内的数据。支持项按当前 fact key 顺序确定排列；`evidence_texts` 按当前 evidence key 顺序选择并去重。Java MUST NOT 使用 `testPoint.description` 代替正式正文，MUST NOT 接受调用方扩大事实或证据闭包。

KEE 与 Java SHALL 使用相同的字段类别：title 对应 `function`；preconditions 对应 `roles`、`trigger_conditions`、`business_rules`、`permissions`、`state_changes`；action 对应 `trigger_conditions`、`inputs`；expected 对应 `outputs`、`state_changes`、`exception_handling`、`external_dependencies`。任一字段 MAY 逐字复制一个 bound `evidence_texts` 中完整出现的片段，但 MUST NOT 拼接零散片段或以一个类别中的词授权另一类别新增的业务含义。

#### Scenario: 正式正文进入 KEE 输入
- **WHEN** 当前正式测试点绑定一个已验收 requirement fact 和两个授权 evidence keys
- **THEN** Java SHALL 在同一次 KEE 请求的 `formal_supports` 中发送该事实的全部正式字段以及这两个 key 对应的确定顺序证据正文

#### Scenario: 只发送当前测试点的支持闭包
- **WHEN** confirmed function 还包含当前测试点未引用的其他 fact 或 evidence
- **THEN** Java SHALL 排除未引用数据，且事实、证据或正文缺失时 SHALL 在网络调用前失败关闭

#### Scenario: 重启后输入保持相同
- **WHEN** 上游已完成且新 coordinator 从数据库恢复尚未执行的测试用例工作
- **THEN** Java SHALL 从耐久 `AcceptedFact` 重建与首次执行字节语义相同的 `formal_supports`，不得依赖进程内 test point 描述或模型响应

### Requirement: [REQ-FTG-005] 正式需求事实字段必须由其绑定证据直接支持
Java SHALL 在 requirement review 结果原子持久化前，对每个正式 requirement fact 的 `function` 以及 `roles`、`trigger_conditions`、`inputs`、`business_rules`、`outputs`、`permissions`、`state_changes`、`exception_handling`、`external_dependencies` 中每个非空项分别验证。每项 MUST 在该 fact 引用、属于当前 task/material/slice 且已完整遍历的至少一条 parsed-unit 正文中连续直接出现；evidence key 合法本身 MUST NOT 证明 fact 正文有依据。

requirement fact 的直接证据校验 SHALL 使用 Unicode NFKC、`Locale.ROOT` 小写，并移除 `Character.isWhitespace` 或 `Character.isSpaceChar` 识别的全部 Unicode 空白；标点和其他字符 MUST 保留。该规则只容忍 PDF 布局换行、普通空格或其他 Unicode 空格造成的断词，MUST NOT 接受删标点、同义改写、语义拆写、词序重组或跨 evidence 的零散拼接。坏例 quote SHALL 使用独立规则：NFKC、`Locale.ROOT` 小写、默认正则 `\s+` 压缩为单个普通空格后 `strip`，MUST NOT 因 fact 的布局空白容错而忽略坏例内部空格。任一 fact 字段不满足时，Java SHALL 以 `business_validation_failed` 终止该 work item，完整 review result 的 fact、finding 和 reference binding 均 SHALL 零接收，且 MUST NOT 进入 Java 模型瞬时错误重试。

#### Scenario: PDF 布局空白拆开正式事实词语
- **WHEN** 同一 cited parsed-unit 原文仅因 PDF 排版在词语内部出现换行、普通空格或 Unicode 空格，而 fact 保留正常读者文本
- **THEN** Java SHALL 在保留标点及其他字符后忽略这些布局空白，并允许直接证据连续匹配

#### Scenario: 删除标点或跨证据拼接仍然无效
- **WHEN** fact 只有删除原文标点，或拼接两条 cited parsed-unit 的零散片段后才能匹配
- **THEN** Java SHALL 拒绝整个 requirement review result

#### Scenario: 坏例引用不得借用 fact 空白容错
- **WHEN** bad-source 原文在词语之间含内部空格，而 quote 删除该内部空格
- **THEN** Java SHALL 按坏例独立 normalizer 拒绝该 finding

#### Scenario: cited key 合法但事实正文改写或新增
- **WHEN** evidence 原文仅写“提交账号和正确密码”及登录结果，而 fact 删除“正确”、拆写“密码必须正确/用户必须已注册”或新增“会话状态由未登录变为已登录”
- **THEN** Java SHALL 拒绝整个 requirement review result，且不得将该 fact 用作后续 `formal_supports`

#### Scenario: 同任务正控事实全部来自原文片段
- **WHEN** fact 的各非空字段均可在其 cited parsed-unit 原文中按规定归一化后连续找到
- **THEN** Java SHALL 在其他 review 业务规则也满足时允许该 fact 进入原子接收

#### Scenario: 每类 requirement fact 叙述字段使用同一依据门禁
- **WHEN** function 或任一角色、触发、输入、规则、输出、权限、状态、异常、依赖项缺少直接 evidence 片段
- **THEN** Java SHALL 对任一字段族执行相同失败关闭判定，不得只校验部分字段族

### Requirement: [REQ-FTG-006] 高粒度用例请求与结果必须严格闭合
Java SHALL 在 `functional-testcase-design.input` 中支持可选 `authoring_information {author,date}`，并在每个 `formal_supports` 项中携带 support 级非空 `evidence_keys`。formal point 的 support fact keys SHALL 与测试点 requirement fact keys 精确闭合；每项 support evidence keys MUST 属于测试点证据闭包，且所有 support evidence keys 并集 MUST 精确等于测试点 evidence keys。general-experience point SHALL 显式发送空 supports。

support 的 evidence keys 与 evidence texts SHALL 按测试点 evidence key 的冻结顺序成对构造。不同 evidence key 即使对应相同正文也 MUST 保留两项，MUST NOT 按正文去重而破坏 key/text 映射。

Java SHALL 完整接收并严格验证 testcase 的 `case_key`、`name`、`title`、`priority`、`preconditions`、`initialization`、`inputs`、`steps`、`expected_results`、`evaluation_criteria`、`result_evaluation_criteria`、`termination_conditions`、`result_collection`、`authoring_information`、`requirement_fact_keys`、`evidence_keys`、`case_status` 和 `missing_information`。priority 只能为 high/medium/low；初始化四类数组必须显式存在；输入 nature/source/method/authenticity 只能使用冻结枚举；step_no 必须从 1 连续。请求有 author/date 时结果 MUST 精确回显；请求省略时结果两字段 MUST 为空字符串。function path SHALL 由 Java confirmed function 派生，MUST NOT 信任模型输出。

formal testcase evidence keys MUST 精确等于其选择 requirement facts 对应 support evidence keys 的并集。formal reader field 必须在 NFKC、大小写和空白规范化后等于一个完整允许类别值或完整 evidence_text，MUST NOT 只匹配任意短子串、删除标点、跨类别搬运或拼接。只有冻结的五条通用中文测试语句 MAY 脱离业务来源；其余业务内容必须直接支持。

#### Scenario: support 证据并集未闭合
- **WHEN** 某 support evidence key 越界，或所有 support evidence keys 并集少于/多于测试点 evidence keys
- **THEN** Java SHALL 在 HTTP 调用前失败关闭

#### Scenario: formal 用例缩短正式片段
- **WHEN** 来源完整值为“账号登录成功”而 formal name/title/reader field 只返回“登录”
- **THEN** Java SHALL 拒绝完整结果且零业务行接收

#### Scenario: 作者日期精确回显
- **WHEN** 请求携带 author/date
- **THEN** 每条返回用例 SHALL 精确回显；未携带时两字段 SHALL 为空字符串而非模型编造值

### Requirement: [REQ-FTG-007] 评审 finding 必须可核验且保持待确认边界
Java SHALL 接收并验证 13 个冻结 `root_cause_kind`、`affected_scope{unit_keys,summary}`、`bad_source_example{evidence_key,quote}` 和 `proposed_good_example{status,text}`。同一有界响应内相同 root cause MUST 拒绝；affected unit keys MUST 是 finding evidence 子集；坏例 quote MUST 是其指定 parsed-unit 的规范化连续原文；建议好例 status 只能为 `pending_confirmation` 且 text MUST 含“待需求方确认”。issue type、description、scope summary、test-design impact 和两个 recommendation MUST 含中文分析；真实坏例 quote MAY 保留英文。

评审 finding、坏例、建议或 proposed good example MUST NOT 进入 requirement fact、formal test point、formal testcase 或 formal coverage。

#### Scenario: 英文坏例与中文分析
- **WHEN** 当前材料坏例是英文原文
- **THEN** quote MAY 保留英文，但问题、范围、分析、影响和建议 SHALL 使用中文

#### Scenario: 建议好例没有待确认标识
- **WHEN** proposed good example status 不是 pending_confirmation 或正文不含“待需求方确认”
- **THEN** Java SHALL 拒绝完整 review result

### Requirement: [REQ-FTG-008] 同一任务跨调用按根因耐久合并
Java SHALL 在同一 task 的多个有界 review 调用之间按 `root_cause_kind` 合并 finding，合并 affected scope/evidence，并至少保留一个真实坏例和一个明确待确认的建议好例。不同 root cause MUST NOT 合并。该合并 MUST 在数据库事务和唯一约束下保持幂等、并发安全、重启可恢复，不能依赖进程内 Map。

#### Scenario: 相同根因跨两个切片
- **WHEN** 同一任务两个已验证切片返回相同 root cause 和不同 evidence/scope
- **THEN** 详情和 Excel SHALL 只显示一个合并 finding，且范围和证据不丢失

#### Scenario: 不同根因文本相似
- **WHEN** 两个 finding 描述相近但 root cause 不同
- **THEN** Java SHALL 保持两条独立记录，不得模糊合并

### Requirement: [REQ-FTG-009] 高粒度数据必须原子持久化并同源交付
Java SHALL 使用 V12 之后首个空闲增量 Flyway 版本持久化高粒度 testcase、review finding 和引用，且 validator 完整通过后才在一个事务中写入。任一业务校验、引用、合并冲突或数据库失败 MUST 零部分接收；恢复路径 SHALL 只读取已验收持久化记录。

任务详情和 Excel SHALL 从同一 task、同一持久化投影读取。页面 SHALL 显示高粒度执行字段、formal/pending 和中文来源语义；Excel SHALL 恰好包含 `需求与功能清单审查发现`、`测试用例` 两个 Sheet，并包含冻结评审和用例字段。两处 MUST NOT 展示 raw JSON、Markdown、内部枚举、unit/evidence/fact/case 等机器键或凭据；Excel MUST 防止公式注入。待确认不得计入正式覆盖，用例数量不得按固定 2N 生成。

#### Scenario: 同一用例跨 API、页面和 Excel
- **WHEN** 一个高粒度结果完成原子接收
- **THEN** API、页面和 Excel SHALL 展示同一数据库记录的相同业务语义和 formal/pending 状态

#### Scenario: 旧任务没有新增字段
- **WHEN** FEATURE 或旧 Markdown 任务没有高粒度结构化投影
- **THEN** 既有详情和导出路径 SHALL 保持兼容，不得伪造新增字段

### Requirement: [REQ-FTG-010] 超长材料评审窗口必须在同一任务内确定性二分
当 `requirement-material-quality-review` 的一个连续 parsed-unit 窗口收到 KEE `response_too_large` 时，Java SHALL 在同一 task、同一 material 和同一冻结 inventory 内将该窗口按确定性中点拆为两个非空连续子 work。左右子 work 的全局 ordinal、unit key、content、顺序与授权证据拼接 MUST 精确等于父窗口；Java MUST NOT 截断、拼接、改写或重新解析材料，也 MUST NOT 创建第二个业务 task。

父 work 的当前 attempt、父 work 的 `SPLIT` 状态和两个稳定身份子 work MUST 在一个数据库事务中同时持久化。任一写入或冻结坐标校验失败 SHALL 全部回滚。进程重启后 SHALL 从任务自有的完整 inventory 重建相同子窗口，跳过已拆分父 work 和已完成子 work，不得重复 KEE 调用或业务接收。拆分父 work MUST NOT 计入叶子评审总数、完成数或失败数；既有其他 work 的 accepted hash、fact、finding 和 binding MUST 保持不变。

`response_too_large` MUST NOT 加入自动模型重试白名单。若当前窗口只有一个 unit，Java SHALL 稳定失败关闭且不得继续拆分。其他 KEE 错误和其他 Skill 的处理保持不变。

#### Scenario: 三十二单元窗口被原子拆为两个子 work
- **WHEN** ordinal 33..64 的 32-unit review work 收到 `response_too_large`
- **THEN** Java SHALL 原子保存 `33..48` 与 `49..64` 两个子 work，并只以各自原样的 unit 闭包顺序调用 KEE

#### Scenario: 拆分后重启恢复
- **WHEN** 父 work 已标记 `SPLIT`，左子 work 已完成而右子 work 尚未完成，协调器进程重启
- **THEN** Java SHALL 从冻结 inventory 恢复相同树，只执行右子 work，且既有事实、发现和绑定不得重复

#### Scenario: 最小窗口仍然超长
- **WHEN** 仅含一个 unit 的 review work 收到 `response_too_large`
- **THEN** Java SHALL 保留安全失败证据并终止当前任务，不得创建空窗口、第二个 task 或递归死循环

### Requirement: [REQ-FTG-011] 拆分树显式恢复必须保留未执行兄弟
当结构化任务因一个叶子 work 失败而进入 `FAILED`，且同一任务仍存在递归拆分产生的 `QUEUED` 兄弟时，Java SHALL 允许一次显式用户恢复只重置该唯一失败叶子。任务 MUST 为 ALL/FAILED、无执行槽、冻结材料完整；未完成 work 中 MUST 恰好一个为 `FAILED`，其余 MUST 全部为 `QUEUED`，所有未完成 work MUST 无 accepted hash 和八类业务行。失败叶子的最新 attempt 仍 MUST 满足既有显式重试错误白名单。

恢复事务 SHALL 只把唯一失败叶子改为 `QUEUED` 并恢复任务运行资格；已完成 work、`SPLIT` 父项、排队兄弟、历史 attempt、accepted hash 和业务行 MUST 保持不变。多个失败叶子、任一 `RUNNING` work、排队兄弟已有部分结果或并发状态漂移 MUST 失败关闭。

#### Scenario: 一个失败叶子和多个排队兄弟
- **WHEN** 拆分树只有一个零写入 `structured_output_invalid` 叶子失败，其他尚未执行叶子均为 `QUEUED`
- **THEN** 显式恢复 SHALL 只重置失败叶子，并在同一 task 内继续处理所有排队叶子

#### Scenario: 排队兄弟已有部分结果
- **WHEN** 任一 `QUEUED` 兄弟已拥有 accepted hash 或任一业务行
- **THEN** Java SHALL 拒绝恢复，不得修改 task、失败叶子或排队兄弟

### Requirement: [REQ-FTG-012] 超长功能清单提取窗口必须在同一任务内确定性二分
当 `feature-scope-reconciliation` 的 `extract_function_list` 连续 parsed-unit 窗口收到 KEE 明确返回的 `response_too_large` 时，Java SHALL 在同一 task、同一 function-list material 和同一冻结 inventory 内按确定性中点拆为两个非空连续子 work。左右子 work 的全局 ordinal、unit key、content、顺序和证据闭包拼接 MUST 精确等于父窗口；Java MUST NOT 截断、改写、重新解析、扩大文档范围或创建第二个业务 task。

父 work 当前 attempt、父 `SPLIT` 状态和两个稳定身份子 work MUST 在一个事务中提交。父 work 及两个子 work 的 task、Skill、operation、material、source label、ordinal 和 evidence closure MUST 由事务锁内再次验证；父 work 有 accepted hash 或任一业务行时 MUST 拒绝拆分。进程重启后 SHALL 从任务自有完整 inventory 重建相同子树，跳过 `SPLIT` 父项和已完成子项，不得重复提取、重复写入功能项或依赖进程内状态。

只有 `error.details.type=response_too_large` MAY 触发该拆分；`structured_output_invalid`、业务校验失败及其他错误 MUST 保持失败关闭。`response_too_large` MUST NOT 加入同一 work 的自动模型重试。若当前窗口只有一个 persisted unit，Java SHALL 稳定失败并保留安全诊断，不得继续拆分、生成空窗口或递归循环。

#### Scenario: 三十二单元功能清单窗口被原子拆分
- **WHEN** ordinal 1..32 的 `extract_function_list` work 收到 `response_too_large`
- **THEN** Java SHALL 原子保存 `1..16` 与 `17..32` 两个子 work，并只按原样 unit 顺序分别调用 KEE

#### Scenario: 功能清单拆分后重启恢复
- **WHEN** 父 work 已为 `SPLIT`、左子已完成、右子尚未完成且 Java 进程重启
- **THEN** Java SHALL 从冻结 inventory 只执行右子 work，既有功能项、引用和 accepted hash 不得重复或变化

#### Scenario: 结构错误不得伪装容量错误
- **WHEN** `extract_function_list` 返回 `structured_output_invalid`，或单 unit 窗口返回 `response_too_large`
- **THEN** Java SHALL 失败关闭且不得创建子 work

### Requirement: [REQ-FTG-013] 材料输出归属窗口必须语义规划并耐久恢复
Java SHALL 将 `requirement-material-quality-review` 与 `extract_function_list` 的 `units` 解释为当前调用的输出归属目标。新材料计划 SHOULD 通常包含 8~16 个连续目标 parsed units，并 SHOULD 在该范围内优先结束于可确定识别的章节、表格或功能路径边界；8~16 是规划目标而不是所有材料的固定数量，短材料或最终短尾 MAY 少于 8。Java MUST NOT 固定按 32 个目标单元分片。

Java MAY 发送 `context_units`，但其每项 MUST 是同一 task、同一冻结材料的原样 parsed-unit，MUST 与目标不重叠且 MUST 是目标前后相邻的有界上下文。`units + context_units` 总数 MUST 不超过 32。正式 fact、finding、function item、reference binding 和 allowed evidence MUST 只引用目标 `units`；context MUST NOT 获得输出归属或正式 evidence 权限。

目标 ordinal/keys、context ordinal/keys、材料文档身份和 split lineage MUST 在调用 KEE 前持久化。重启 SHALL 优先恢复这些耐久窗口并只执行未完成叶子；旧记录没有 context/lineage 列值时 SHALL 继续按其已保存目标窗口兼容读取，MUST NOT 用新规划器改变 identity 或重跑完成兄弟。

只有 KEE 公开返回 `response_too_large` 时，Java MAY 在同一 task/material/inventory 中原子二分当前目标 `units`。每个非空子目标的 context MUST 从同一持久化 inventory 重新计算；父 attempt、父 `SPLIT` 和两个带 lineage 的子 work MUST 全有或全无。`structured_output_invalid` 和其他错误 MUST NOT 触发容量拆分；单目标单元仍超限时 MUST 失败关闭。

#### Scenario: 语义边界优先于固定计数
- **WHEN** 完整材料在第 9 个目标单元后出现新章节或新的功能路径区段，且前后窗口均可保持有界
- **THEN** Java SHALL 在该边界结束当前目标窗口，而不是机械等待第 32 个单元

#### Scenario: 上下文只读且不重叠
- **WHEN** 一个目标窗口两侧存在相邻 parsed units
- **THEN** Java MAY 按 ordinal 发送相邻 context，但目标与 context key SHALL 互斥、合计不超过 32，且结果 evidence/binding SHALL 只来自目标 keys

#### Scenario: 容量拆分重新计算上下文
- **WHEN** 一个已持久化目标窗口收到 `response_too_large`
- **THEN** Java SHALL 原子发布两个非空子目标，并从同一 inventory 为每个子目标重新选择相邻 context；不得简单继承父 context 或拆模型输出

#### Scenario: 旧工作记录保持兼容
- **WHEN** 旧任务已持久化一个无 context/lineage 的 32-unit work 或拆分树
- **THEN** Java SHALL 按旧目标 identity 和既有完成状态恢复，跳过完成兄弟且不得按新语义窗口重复注册

### Requirement: [REQ-FTG-014] 功能清单项必须由目标单元直接原文证明
`extract_function_list.function_list_items[*]` SHALL 包含必填非空且最多 512 个 Unicode code points 的 `target_quote`。Java SHALL 在原子接受前验证：quote 经 Unicode NFKC、`Locale.ROOT` 对完整字符串小写并移除 `Character.isWhitespace`/`Character.isSpaceChar` 识别的布局空白后，完整连续存在于该项 `evidence_keys` 引用的至少一个目标 `units` 正文。该规则与 KEE 的 Unicode undetermined-locale full lowercase 对齐；标点和其他字符 MUST 保留，MUST NOT 跨 evidence 拼接、同义改写或仅由 `context_units` 支持。

Java SHALL 按 `/`、`\\`、`→`、`>`、`›`、`»`、`|` 对 `path` 分层，并取最后一个非空段作为最终功能叶子；该叶子经同一规范化后 MUST 完整连续存在于 `target_quote`。context MAY 只补全父级 path 前缀，不得单独证明最终叶子。

Java SHALL 只从目标 `units` 构造 quote 校验正文映射；context key/content MUST NOT 进入该映射、功能项 evidence、binding 或输出归属。完整 extraction 结果 SHALL 在写入前全部通过；任一行缺少 quote、quote 不属于所引目标 evidence 或伪造目标 key 时，当前批次的 function item 和 binding SHALL 零接收。

新接受的 task-owned function item SHALL 耐久保存一个或多个已验证 target quote；跨窗口稳定项合并 SHALL 在任务级行锁事务内去重合并 quote。历史行没有 quote 时 SHALL 保持可读，重启 SHALL NOT 编造或从 context 回填 quote。

#### Scenario: context 只帮助层级理解
- **WHEN** 模型从相邻 context 补全 path 层级，但 `target_quote` 精确来自该项引用的目标单元
- **THEN** Java SHALL 接受该项，并仅将目标 evidence 绑定为来源

#### Scenario: context 原文冒充目标 quote
- **WHEN** 模型引用一个合法目标 evidence key，但 quote 只存在于 context 或另一个未引用目标单元
- **THEN** Java SHALL 拒绝完整 extraction 批次且零业务接受

#### Scenario: context 功能名与无关目标正文拼接
- **WHEN** context 包含“管理员批量删除用户”，目标单元只有“备注”，模型返回 path“用户管理/批量删除”和 target_quote“备”
- **THEN** 即使 quote 属于所引目标正文，Java 仍 SHALL 因最终叶子“批量删除”不在 quote 中而拒绝完整批次

#### Scenario: PDF 布局空白不改变直接原文
- **WHEN** 目标正文为“功能路径：订\n单 提交”，quote 与最终 path 叶子均为“订单提交”
- **THEN** Java SHALL 在移除 Unicode 布局空白后接受该直接原文关系

#### Scenario: 跨语言小写和 Java 空白集合保持确定
- **WHEN** 来源为 `ΟΣ`
- **THEN** quote/path `ος` SHALL 通过而 `οσ` SHALL 拒绝；U+001C SHALL 被移除而 U+0085 SHALL 保留

#### Scenario: 路径规范化后提取最终叶子
- **WHEN** path 使用全角 `／` 或以任一冻结分隔符结尾
- **THEN** Java SHALL 先执行 NFKC，再忽略尾随空段并取最后一个非空叶子；七种冻结分隔符的结果 SHALL 一致

#### Scenario: 历史功能项没有 target quote
- **WHEN** 重启读取 V18 之前已接受且 quote 列为 NULL 的功能项
- **THEN** Java SHALL 保持原 item/path/description/evidence 可读并返回空 quote 列表，不得编造来源

### Requirement: [REQ-FTG-015] 逐材料结构化调用必须使用当前文档的单文档授权
Java SHALL 将完整任务材料范围与一次逐材料 HTTP 调用的授权范围分离。任务快照、材料清单和 parsed-unit inventory MUST 继续保存并遍历用户选择的全部文档；每次 `requirement-material-quality-review` 或 `extract_function_list` 调用的 `knowledge_ids` 及唯一 `system_scope.knowledge_ids` MUST 只包含当前耐久材料文档。`material_key` MUST 保持调用方不透明键，不得替代文档授权身份。

协调器 SHALL 对首次窗口、容量拆分子窗口、显式恢复和重启恢复应用相同规则。收窄调用授权 MUST NOT 删除任务快照中的其他文档、跳过其他材料、重复已完成兄弟或改变窗口的目标/context parsed-unit 闭包。任务级 `reconcile_page` 与 `functional-testcase-design` SHALL 继续使用完整冻结范围。

#### Scenario: 功能清单提取只授权功能清单文档
- **WHEN** 任务冻结范围同时包含需求材料与功能清单，协调器执行某个 `extract_function_list` 窗口
- **THEN** 该次 KEE 调用 SHALL 只授权当前功能清单文档，而后续任务级核对仍 SHALL 看见完整冻结范围

#### Scenario: 需求材料逐份评审且不缩小任务范围
- **WHEN** 任务包含多份非功能清单材料
- **THEN** Java SHALL 逐份遍历全部材料，并让每次 review 调用只授权当前被评审文档

#### Scenario: 持久窗口恢复保持材料身份
- **WHEN** 进程重启或显式恢复只执行一个未完成的 review/extraction 叶子窗口
- **THEN** Java SHALL 从该 work 的耐久材料身份派生同一单文档授权，且不得重跑已完成兄弟

### Requirement: [REQ-FTG-016] 历史功能清单大窗口必须在网络调用前原子拆分
当显式恢复或重启遇到 V17 之前已持久化、`material_document_id` 为空的 `FEATURE_SCOPE_EXTRACT` 叶子时，Java SHALL 保留其旧 identity 和冻结 inventory。若该叶子尚未接受且目标 `units` 数量大于 16，Java SHALL 在 claim、attempt 和 KEE 调用之前按确定性中点拆成两个非空历史子 work；两个子目标的 unit key、ordinal、content 和顺序拼接 MUST 精确等于父目标，每个子目标 MUST 不超过 16。

父 work 行、两个子 work 和完整 evidence 分区 MUST 在一个锁定事务内发布。父项必须为 `QUEUED`、accepted hash 为空且八类 work-owned 业务行均为零；事务 SHALL 把父项改为 `SPLIT`，但 MUST NOT 新建或改写父 attempt。任一坐标冲突、部分业务行、并发状态漂移或第二个子项注册失败 MUST 全部回滚。并发预拆分只有一个调用可发布子项；重复观察已 `SPLIT` SHALL 幂等返回。

该规则只迁移历史 `FEATURE_SCOPE_EXTRACT` 窗口，MUST NOT 依据 `model_execution_failed` 或其他错误类型触发，MUST NOT 扩张到 requirement review 或其他 operation。新任务的 8~16 语义窗口/context 规划保持不变；历史子项继续保持旧输入没有 context 的兼容形状。已完成兄弟 SHALL 在预拆分检查前跳过，重启 SHALL 只领取未完成子叶子。单目标窗口和不超过 16 个目标单元的历史窗口 SHALL 不拆分。

#### Scenario: 历史 32 单元窗口在联网前完整迁移
- **WHEN** 显式恢复把一个零写入的历史 `FEATURE_SCOPE_EXTRACT` 1..32 叶子恢复为 `QUEUED`
- **THEN** Java SHALL 在任何 KEE 调用前原子发布 1..16 与 17..32 两个子 work，32 个目标单元全部且仅归属一次

#### Scenario: 已完成兄弟不被迁移或重跑
- **WHEN** 同一历史材料已有完成窗口，另一个大窗口需要预拆分
- **THEN** Java SHALL 保留完成 work 的 accepted hash 和业务行，并只拆分未完成大窗口

#### Scenario: 预拆分并发或事务失败
- **WHEN** 两个执行者并发迁移同一父项，或任一子项冻结坐标无效
- **THEN** 最多一个事务发布同一对子项；失败事务 SHALL 保持父项和全部子项均未改变
