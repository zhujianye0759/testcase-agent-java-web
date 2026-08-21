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

校验 SHALL 使用 Unicode NFKC、ASCII 大小写及空白/标点归一化后的连续包含关系，MUST NOT 接受同义改写、语义拆写、词序重组或跨 evidence 的零散拼接。任一 fact 字段不满足时，Java SHALL 以 `business_validation_failed` 终止该 work item，完整 review result 的 fact、finding 和 reference binding 均 SHALL 零接收，且 MUST NOT 进入 Java 模型瞬时错误重试。

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
