## ADDED Requirements

### Requirement: [REQ-FTG-001] 正式用例读者字段必须有直接正式依据
Java SHALL 在原子持久化前，对 `case_status=formal` 的 title、每条 precondition、每个 step.action 和 step.expected 分别验证其业务内容由该用例绑定且属于当前测试点闭包的正式 requirement fact 或 evidence 正文直接支持。合法的 `requirement_fact_keys` 和 `evidence_keys` 只证明引用闭包，MUST NOT 单独证明读者正文有依据。校验 MUST NOT 使用全局业务关键词黑名单；同一术语在相应正式来源中明确出现时 SHALL 可被接受。

Java MAY 只移除以下不携带业务含义且按字段固定的前缀包装：title 的“验证”“确认”“正常”，precondition 的“前提：”“前置条件：”，action 的“操作：”“执行：”，expected 的“预期：”“预期结果：”。移除包装后，剩余完整业务片段仍 MUST 由单个允许来源直接覆盖；MUST NOT 把零散词语拼接为依据或借包装接受额外业务词。

#### Scenario: 泛称账号不得扩成具体账号类型
- **WHEN** 绑定正式事实和证据只明确“账号”，formal 用例的 title、precondition、action 或 expected 增加“用户名”“手机号”或“邮箱”
- **THEN** Java SHALL 拒绝完整候选结果且零业务行接收

#### Scenario: 认证机制没有正式依据
- **WHEN** 绑定正式事实只描述登录状态变化，formal 用例增加 Token、Session、登录接口或受保护资源等未直接支持的机制
- **THEN** Java SHALL 拒绝完整候选结果且不得计入正式覆盖

#### Scenario: 相同术语已有正式依据
- **WHEN** formal 用例的业务片段在其绑定正式事实或 evidence 的相应正文中明确出现
- **THEN** Java SHALL 在其他业务规则也满足时允许该用例通过 grounding 校验

#### Scenario: 固定叙述包装不改变依据边界
- **WHEN** formal 用例只在已有直接依据的完整业务片段前增加对应字段允许的固定包装
- **THEN** Java SHALL 允许该包装，但包装后的用户名、手机号、Token 等额外业务内容仍 SHALL 被拒绝

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
