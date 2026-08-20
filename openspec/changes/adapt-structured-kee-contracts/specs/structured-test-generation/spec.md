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
Java SHALL 验证每个 reconciliation 的来源 key、证据 key、classification 和 confirmation_status，并 SHALL 使当前核对工作项中的每个功能清单条目和需求事实进入可追溯终态。`pending_confirmation`、`insufficient_evidence`、冲突、拆分、合并和重复 MUST 保留其原始状态，不得自动提升为已确认精确匹配。

#### Scenario: 所有来源均取得终态
- **WHEN** reconciliation 集合覆盖当前工作项全部功能清单条目和需求事实且引用一致
- **THEN** Java SHALL 原子保存核对结果并更新该工作项处理终态

#### Scenario: 任一来源未处置
- **WHEN** 至少一个输入条目或事实没有任何合法终态关系
- **THEN** Java SHALL 拒绝该结果且不得冻结最终功能范围

### Requirement: [REQ-STG-004] Java 按需求事实形成非固定数量测试点
Java SHALL 根据已验证需求事实、业务规则、边界、权限、状态、异常和依赖形成数量不固定的测试点，不得按功能数推导固定 `2N`。测试点 type MUST 属于冻结的七类，basis SHALL 为 `formal_requirement` 或 `general_experience`，且缺失信息 SHALL 显式保留。

#### Scenario: 一个功能需要多个测试点
- **WHEN** 正式需求事实同时包含正常行为、边界、权限和状态转换规则
- **THEN** Java SHALL 分别建立所需测试点
- **AND** SHALL NOT 将其压缩成固定正向/反向两点

### Requirement: [REQ-STG-005] 用例状态必须与测试点依据一致
Java SHALL 要求结果 `function_key` 和 `test_point_key` 与请求完全一致，并验证用例内需求事实和证据引用。每个 `formal_requirement` 测试点 MUST 至少接收一条 `formal` 用例；`general_experience` 测试点产生的用例 MUST 全部为 `pending_confirmation`，不得计入正式覆盖。一个测试点 SHALL 接受 1..50 条合法用例和每条 1..50 个连续步骤，不要求固定数量或正反成对。

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

#### Scenario: 持久化中途失败
- **WHEN** 保存任一业务行、引用或尝试终态失败
- **THEN** Java SHALL 回滚该工作项的全部写入

### Requirement: [REQ-STG-007] 处理状态与覆盖结果分别计算
Java SHALL 使用独立字段表示处理状态和覆盖结果。任务只有在全部 parsed-units 完整遍历、材料切片终态、功能核对终态、冻结范围有效、所有正式测试点至少一条正式用例及制品校验通过时，才 MAY 进入 `COMPLETED`；任何部分成功 MUST 保持 `PARTIAL` 或相应失败/取消状态。

#### Scenario: 工作处理完成但正式覆盖不足
- **WHEN** 所有远程调用均已终止但至少一个正式测试点没有正式用例
- **THEN** Java SHALL 将处理状态与覆盖不足分别展示
- **AND** SHALL NOT 把任务标记为完整覆盖的 `COMPLETED`

### Requirement: [REQ-STG-008] 任务材料必须满足项目、正式来源和文件唯一性前置条件
Java SHALL 只冻结同一知识库、系统、版本和非空项目下的材料。每个任务 MUST 包含 `function_list`，并 MUST 至少包含 `requirements_spec` 或 `work_order_plan` 之一作为正式需求材料；`prototype` 和 `requirement_list` 只能补充，不得替代上述正式材料。Java SHALL 使用 KEE 文档元数据中的文件 SHA-256 对任务内全部材料做唯一性校验；哈希缺失或重复 MUST 在调用结构化 Skill 前失败关闭，同内容重复材料不得增加证据权重。

#### Scenario: 同一文件以不同名称重复进入任务
- **WHEN** 两个所选文档的文件 SHA-256 相同
- **THEN** Java SHALL 拒绝创建该任务
- **AND** SHALL NOT 调用任何结构化 Skill

#### Scenario: 只有一图一表补充材料
- **WHEN** 所选材料包含功能清单和原型或需求清单，但没有需求规格说明书或工单方案
- **THEN** Java SHALL 拒绝创建该任务并提示缺少正式需求材料
