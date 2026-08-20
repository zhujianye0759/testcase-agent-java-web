## ADDED Requirements

### Requirement: [REQ-STG-001] 模型结果必须先通过 Java 业务校验
Java SHALL 在持久化前验证所有 `*_key` 和 `evidence_key` 均引用当前任务、当前工作项和当前冻结范围内已登记对象。任何未知、跨材料、跨任务、跨范围、已退休或未完整遍历证据 MUST 使整个工作项失败；Java MUST NOT 自动补字段、裁剪数组、修正引用或接受部分结果。

#### Scenario: 所有引用均属于当前工作项
- **WHEN** 一个结构化结果通过 KEE 结构校验且所有引用均解析到当前允许集合
- **THEN** Java SHALL 继续执行该 Skill 的业务不变量校验

#### Scenario: 返回未知或越界引用
- **WHEN** 任一 key 不存在或 evidence 不属于当前允许集合
- **THEN** Java SHALL 原子拒绝整个结果且不保存任何业务行

### Requirement: [REQ-STG-002] 需求材料审查结果保持证据角色边界
Java SHALL 接受各 0..200 的 `requirement_facts` 和 `review_findings`，但两者合计 MUST 至少一项。Java SHALL 验证 fact/finding key、证据归属和处理等级，并 SHALL 只允许 `requirements_spec` 或 `work_order_plan` 形成正式需求事实；`prototype` 和 `requirement_list` 只能形成补充审查信息，不能单独支撑正式需求事实、正式覆盖或正式缺陷结论。

#### Scenario: 只有审查发现而没有需求事实
- **WHEN** `requirement_facts` 为空、`review_findings` 非空且所有证据与角色合法
- **THEN** Java SHALL 接受该材料审查终态
- **AND** SHALL NOT 虚构需求事实

#### Scenario: 两个结果数组都为空
- **WHEN** `requirement_facts` 与 `review_findings` 均为空
- **THEN** Java SHALL 拒绝该结果并产生零业务持久化

### Requirement: [REQ-STG-003] 功能双向核对覆盖所有输入来源
Java SHALL 先对每个 `extract_function_list` 结果验证 evidence key 属于当前功能清单材料和当前切片；只有全部条目通过后才为条目生成任务内稳定 `item_key`，并在跨切片聚合时按稳定业务内容和证据进行确定性去重。稳定身份的每个 UTF-8 字段 MUST 使用固定宽度长度前缀后再参与哈希，MUST NOT 使用字段内也可出现的 NUL 或其他哨兵字符拼接边界。模型输出 MUST NOT 决定 item key，Java MUST NOT 解析或猜测 Excel Sheet/行/列层级。`function_list_items` 为空 SHALL 作为该切片的合法完整终态。新 item key 只有在该工作项数据库事务提交成功后才 MAY 发布到任务注册表；回滚 MUST 保持其不可见。随后 Java SHALL 验证每个 `reconcile` reconciliation 的来源 key、证据 key、classification 和 confirmation_status，并 SHALL 使当前核对工作项中的每个功能清单条目和需求事实进入可追溯终态。`pending_confirmation`、`insufficient_evidence`、冲突、拆分、合并和重复 MUST 保留其原始状态，不得自动提升为已确认精确匹配。

#### Scenario: 功能清单切片形成稳定条目
- **WHEN** `extract_function_list` 返回的每个条目只引用当前功能清单切片的合法 evidence key
- **THEN** Java SHALL 生成稳定任务内 item key 并原子接收该切片
- **AND** SHALL NOT 接收模型提供的 item key 或推断原始 Excel 坐标

#### Scenario: 功能清单切片没有提取到条目
- **WHEN** 合法 `extract_function_list` 结果的 `function_list_items` 为空
- **THEN** Java SHALL 原子记录该切片处理完成且不生成条目

#### Scenario: 条目持久化回滚
- **WHEN** 新 stable item key 已计算但其数据库写入或引用绑定失败
- **THEN** Java SHALL 回滚全部写入且 SHALL NOT 将该 key 发布到后续 reconcile 可见的注册表

#### Scenario: 所有来源均取得终态
- **WHEN** reconciliation 集合覆盖当前工作项全部功能清单条目和需求事实且引用一致
- **THEN** Java SHALL 原子保存核对结果并更新该工作项处理终态

#### Scenario: 任一来源未处置
- **WHEN** 至少一个输入条目或事实没有任何合法终态关系
- **THEN** Java SHALL 拒绝该结果且不得冻结最终功能范围

### Requirement: [REQ-STG-004] Java 按需求事实形成非固定数量测试点
Java SHALL 根据已验证需求事实、业务规则、边界、权限、状态、异常和依赖形成数量不固定的测试点，不得按功能数推导固定 `2N`。同一事实字段包含多个非空值时，Java SHALL 为每个值分别形成测试点或形成显式包含全部值的可审计聚合，MUST NOT 静默丢弃第 2..N 项；稳定测试点 key SHALL 包含该值或聚合的稳定身份，并 SHALL 对每个 UTF-8 身份字段使用固定宽度长度前缀编码，禁止 NUL 分隔歧义。测试点 type MUST 属于冻结的七类，basis SHALL 为 `formal_requirement` 或 `general_experience`，且缺失信息 SHALL 显式保留。

#### Scenario: 一个功能需要多个测试点
- **WHEN** 正式需求事实同时包含正常行为、边界、权限和状态转换规则
- **THEN** Java SHALL 分别建立所需测试点
- **AND** SHALL NOT 将其压缩成固定正向/反向两点

### Requirement: [REQ-STG-005] 用例状态必须与测试点依据一致
Java SHALL 要求结果 `function_key` 和 `test_point_key` 与请求完全一致，并验证用例内需求事实和证据引用。每条 `formal` 用例 MUST 同时引用至少一个当前正式需求事实和至少一个当前允许证据。每条 `pending_confirmation` 用例无论来自哪种测试点 basis，均 MUST 包含至少一条非空 `missing_information`。每个 `formal_requirement` 测试点 MUST 至少接收一条 `formal` 用例；`general_experience` 测试点产生的用例 MUST 全部为 `pending_confirmation`，不得计入正式覆盖。一个测试点 SHALL 接受 1..50 条合法用例和每条 1..50 个连续步骤，不要求固定数量或正反成对。

#### Scenario: 正式测试点取得正式用例
- **WHEN** 正式测试点返回至少一条引用合法的 `formal` 用例
- **THEN** Java SHALL 将该测试点计入正式覆盖

#### Scenario: 通用经验被标为正式用例
- **WHEN** `general_experience` 测试点返回 `formal` 用例
- **THEN** Java SHALL 拒绝整个结果而不是更改 case_status

#### Scenario: 待确认经验用例被接受
- **WHEN** 通用经验测试点只返回引用合法的 `pending_confirmation` 用例
- **THEN** Java MAY 保存并展示这些候选
- **AND** MUST NOT 将其计入正式覆盖

### Requirement: [REQ-STG-006] 每个工作项原子接收且失败不污染已验收结果
Java SHALL 在同一事务中保存一个已完整通过业务校验的 Skill 结果、引用绑定和尝试终态。失败或重试 MUST 产生零部分业务结果；重试 SHALL 只替换当前失败工作项，不得覆盖其他已验收材料、核对或测试点结果。

所有会跨 work item 被引用的 fact、finding、function-list item、reconciliation、test-point 和 testcase key SHALL 具有任务级数据库唯一性，MUST NOT 依赖进程内 registry 证明跨切片或重启后的唯一。模型产生的 key 在进入任务总账前 SHALL 由 Java 绑定到任务/来源工作身份；不同工作项再次写入相同任务 key 时 SHALL 原子冲突并回滚，不得形成歧义 join。多个 extraction 切片产生相同 Java stable item key 时，Java SHALL 以原子 upsert 后锁读的方式核对规范化业务文本一致并合并各自已授权证据；业务文本冲突 SHALL 失败关闭。页面、核对输入和 Excel MUST 只读取任务级唯一条目，不得因跨切片或重启回放重复展示。

引用绑定 SHALL 明确持久化 `subject_type`，并将其纳入绑定主键和查询条件；不同业务类型 MAY 使用相同 subject key，MUST NOT 被合并或产生主键冲突。

数据库工作项中冻结的 skill、operation、material key、切片 evidence closure、lease owner 和 lease expiry SHALL 是接收授权的唯一真相。Java SHALL 在 accept/fail 的同一锁定事务中重读并验证这些值，MUST NOT 信任调用方可构造的 claim 字段扩大权限。`feature-scope-reconciliation` 工作项 MUST 区分 `extract_function_list` 与 `reconcile`；extract 注册缺少材料、来源标签或非空切片 evidence closure 时 SHALL 失败关闭。

Java SHALL 对工作项重试设置固定最大尝试数，并只允许 `model_unavailable` 和 `model_execution_failed` 这两类短暂模型依赖错误最多再次领取一次。`structured_output_invalid` 已包含 KEE 单次调用内的格式修复，Java MUST NOT 再次领取；`invalid_request`、`forbidden`、scope/业务校验失败及其他非白名单失败也 SHALL 终止当前工作项。持久化的 `failure_type` SHALL 来自固定枚举/白名单，MUST NOT 接受任意异常文本、材料、凭据或其他潜在敏感值。

过期 RUNNING 租约 SHALL 被原子回收，旧 attempt 随即失效；过期、owner 错误或已被新 attempt 替代的 claim MUST NOT accept/fail。相同 task+identity 的并发注册 SHALL 原子收敛为同一 work id 和一行，不得把唯一约束冲突暴露为调用失败。相同 task+identity 的重放只有在 skill、operation、ordinal 范围、material、source label、evidence closure、function key 和 test-point key 等全部冻结业务坐标逐项相同时才可返回原 work id；任一坐标不同 SHALL 作为幂等冲突失败关闭，并且不得修改原工作项。

#### Scenario: 持久化中途失败
- **WHEN** 保存任一业务行、引用或尝试终态失败
- **THEN** Java SHALL 回滚该工作项的全部写入

#### Scenario: 跨切片返回相同稳定功能项
- **WHEN** 两个已验证 extraction 切片生成相同 Java stable item key 且业务文本一致
- **THEN** Java SHALL 只保留一个任务级功能项并合并两片的授权证据
- **AND** 重启后重建 registry 或回放相同结果不得产生第二行

#### Scenario: 跨工作项模型 key 冲突
- **WHEN** 第二个工作项或重启后的 registry 尝试以同一任务 key 保存不同 fact、finding、reconciliation、test-point 或 testcase 业务内容
- **THEN** Java SHALL 依靠任务级耐久唯一约束原子拒绝当前工作项
- **AND** 已验收业务行和引用总账 SHALL 保持不变

#### Scenario: 并发首次写入同一稳定功能项
- **WHEN** 两个 extraction 切片并发首次接受相同 `(task_id,item_key)`
- **THEN** Java SHALL 通过原子 upsert 收敛为一行、锁定核对文本并合并授权 evidence
- **AND** MUST NOT 依赖未命中查询的 gap lock 或默认隔离级别

#### Scenario: 调用方伪造更宽切片闭包
- **WHEN** claim 携带的材料或 evidence closure 与数据库冻结工作项不一致
- **THEN** Java SHALL 在同一锁事务失败关闭且保存零业务行

#### Scenario: 过期 claim 与新 attempt 竞争
- **WHEN** RUNNING 租约已过期并被新 attempt 原子接管
- **THEN** 旧 attempt SHALL 失效且不得 accept/fail 覆盖新 attempt

#### Scenario: 并发注册同一工作身份
- **WHEN** 两个线程同时注册相同 task+identity
- **THEN** 两方 SHALL 得到同一 work id 且数据库只保留一行

#### Scenario: 同一身份携带不同冻结载荷重放
- **WHEN** 调用方用相同 task+identity 重放不同 operation 或不同 evidence closure
- **THEN** Java SHALL 报告幂等冲突且保持原工作项完全不变

#### Scenario: KEE 结构输出无效
- **WHEN** KEE 在其一次格式修复后仍返回 `structured_output_invalid`
- **THEN** Java SHALL 终止当前工作项且不得再次领取

#### Scenario: 未知失败类型
- **WHEN** 调用方尝试保存白名单外的 failure type 或异常文本
- **THEN** Java SHALL 在更新数据库前拒绝该值并保持当前 claim 状态不变

### Requirement: [REQ-STG-007] 处理状态与覆盖结果分别计算
Java SHALL 使用独立字段表示处理状态和覆盖结果。任务级处理状态的数据库值和 wire enum SHALL 精确为 `PENDING|RUNNING|COMPLETED|FAILED|CANCELLED`；任务级覆盖结果 SHALL 精确为 `PENDING|COMPLETE|PARTIAL|UNABLE_TO_GENERATE`。处理状态只表达程序是否仍在运行、已完成、失败或取消；覆盖结果独立表达正式材料与正式测试点覆盖完整、部分完整或无法生成。当全部计划工作均已终态且不存在执行失败时，处理状态 SHALL 为 `COMPLETED`，即使材料有缺口、正式覆盖不足或没有可接受正式结果；此时覆盖结果 SHALL 分别保留 `PARTIAL` 或 `UNABLE_TO_GENERATE`。旧任务/交付外层的 `PARTIAL` MUST NOT 出现在 structured processing status；单个通用经验工作项的 `NOT_APPLICABLE` MAY 作为内部值，但 MUST NOT 进入任务级覆盖投影。页面不得直接展示未知原始枚举。

#### Scenario: 工作处理完成但正式覆盖不足
- **WHEN** 所有远程调用均已终止但至少一个正式测试点没有正式用例
- **THEN** Java SHALL 将处理状态标记为 `COMPLETED`
- **AND** SHALL 将覆盖结果独立标记为部分完整或无法生成

#### Scenario: 没有可接受正式结果
- **WHEN** 所有计划工作无执行失败地终止但正式结果数量为零
- **THEN** structured processing status SHALL 为 `COMPLETED`
- **AND** structured coverage status SHALL 为 `UNABLE_TO_GENERATE`

#### Scenario: 任务取消
- **WHEN** 结构化任务在终态前被用户取消
- **THEN** structured processing status SHALL 为 `CANCELLED`

### Requirement: [REQ-STG-008] 任务材料必须满足项目、正式来源和文件唯一性前置条件
Java SHALL 只冻结同一知识库、系统、版本和非空项目下的材料。每个任务 MUST 包含 `function_list`，并 MUST 至少包含 `requirements_spec` 或 `work_order_plan` 之一作为正式需求材料；`prototype` 和 `requirement_list` 只能补充，不得替代上述正式材料。Java SHALL 使用 KEE 文档元数据中的文件 SHA-256 对任务内全部材料做唯一性校验；哈希缺失或重复 MUST 在调用结构化 Skill 前失败关闭，同内容重复材料不得增加证据权重。

#### Scenario: 同一文件以不同名称重复进入任务
- **WHEN** 两个所选文档的文件 SHA-256 相同
- **THEN** Java SHALL 拒绝创建该任务
- **AND** SHALL NOT 调用任何结构化 Skill

#### Scenario: 只有一图一表补充材料
- **WHEN** 所选材料包含功能清单和原型或需求清单，但没有需求规格说明书或工单方案
- **THEN** Java SHALL 拒绝创建该任务并提示缺少正式需求材料
