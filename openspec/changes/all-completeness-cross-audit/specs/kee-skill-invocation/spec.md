## ADDED Requirements

### Requirement: [REQ-KSI-001] 按审查和生成阶段请求精确的测试专业 Skill
系统 SHALL 在功能清单/需求双向核对阶段的 agent-chat 请求中传入且仅传入 `skill_names=["feature-scope-reconciliation"]`，并 SHALL 在已冻结单功能的测试用例生成阶段传入且仅传入 `skill_names=["functional-testcase-design"]`。前者 SHALL 仅处理清单/需求双向核对和最终生成范围建议；后者 SHALL 仅为 Java 已冻结的功能设计测试用例。

#### Scenario: 审查阶段请求错误的 Skill
- **WHEN** 审查阶段的请求缺少 `feature-scope-reconciliation`，或包含生成阶段 Skill
- **THEN** Java 不接收审查结果
- **THEN** 系统不得冻结最终功能集合

#### Scenario: 生成阶段请求错误的 Skill
- **WHEN** 已冻结功能的生成请求缺少 `functional-testcase-design`，或包含审查阶段 Skill
- **THEN** Java 不接收该批次
- **THEN** 该功能不得计入已接收测试用例

### Requirement: [REQ-KSI-002] 以已验证的独立业务会话加载指定 Skill
系统 SHALL 为每个材料审查单元、最终双向核对和单功能生成批次，以该业务项唯一的 `skill_names` 创建一个隔离 KEE 会话。准备请求 SHALL 保持该业务项的冻结范围，但其 `query` SHALL 是固定非检索 token `你好`，以避免 KEE 将准备说明预执行为 RAG；精确 `skill_names` 是服务端可用范围约束，固定 token 不是 Skill 已加载证据。Java SHALL 在该准备请求的既有 SSE `tool_call` 和 `tool_result` 事件中观察与本阶段精确名称相符的 `read_skill` 成功及显式完成。仅在准备成功后，Java 才可在同一会话、相同冻结范围和相同阶段 Skill 下发送该唯一有界业务请求，并在该项结束时关闭会话。Java SHALL NOT 跨业务项复用准备会话。

准备阶段仅可出现名称精确匹配本阶段要求的 `read_skill` 工具链。缺少该事件、名称不符、`read_skill` 失败、工具结果失败、任意其他工具调用、任意 `error`（无论 `done` 值）、流截断或未收到显式完成事件时，Java SHALL 拒绝该业务项，且不得发送其业务审查或生成请求。连接、超时或 408/429/502/503/504 暂态传输错误最多重试三条新会话；范围、HTTP 永久错误、智能体发现失败和合同错误不得重试。已准备会话中的业务请求仍 SHALL 具有显式 SSE 完成终态；会话不匹配、业务流终态错误或流截断时，Java SHALL 拒绝当前结果。Java SHALL NOT 将提示词文本、历史会话名称或普通回答当作 Skill 已加载证据。任一材料审查单元或最终双向核对的准备失败 SHALL 记录当前项失败并终止整个当前审查阶段；生成准备失败 SHALL 终止当前任务，二者均不得继续准备、领取或发送后续业务项。

KEE 可先发送带非空稳定 `tool_call_id`、但 `arguments` 尚缺失的 `read_skill` 声明；Java SHALL 允许该声明但不得将其计为成功，且同一 ID 的后续参数补齐不得被视作第二个未完成调用。只有同一 ID 的精确 `skill_name`、成功 `tool_result` 和显式完成三者齐备才可通过。Java SHALL NOT 将固定 token、提示词文本、历史会话名称或普通回答当作 Skill 已加载证据。

#### Scenario: 准备会话未读取要求的 Skill
- **WHEN** Skill 准备 SSE 到达正常完成，但未观察到精确 Skill 名称的成功 `read_skill`
- **THEN** Java 拒绝整个阶段
- **THEN** Java 不发送任何材料审查或测试用例生成业务请求

#### Scenario: 错误 Skill 或非终态错误不能被后续事件抵消
- **WHEN** 准备 SSE 先出现名称不符的 `read_skill`，或在精确 Skill 成功后出现 `done=false` 的 `error`
- **THEN** Java 立即拒绝准备会话，不等待后续正确 Skill 或完成事件
- **THEN** Java 不发送该业务项，并终止当前审查或生成阶段

#### Scenario: 相邻材料单元使用独立会话
- **WHEN** 两个材料单元或两个冻结功能批次均需要远端调用
- **THEN** 每个业务项均在自己的准备会话中成功读取精确 Skill，再在该同一会话发送唯一业务请求
- **THEN** Java 在每项结束后关闭会话，且不得将前一项的 Skill 证据或历史上下文移植给下一项

#### Scenario: read_skill 返回失败
- **WHEN** 精确 Skill 名称的 `read_skill` 事件或结果表示失败
- **THEN** Java 拒绝该准备会话
- **THEN** 后续受控重试必须创建新的隔离会话并重新观察该 Skill 的成功读取

### Requirement: [REQ-KSI-003] 不为 Skill 引入持久化状态或部署职责
Java MAY 仅在一个准备/业务请求对内暂存已验证的会话 ID、冻结范围和精确 Skill 名称，并 SHALL 在该业务项结束时清除；Java SHALL NOT 校验或持久化 Skill version、hash、manifest、outcome 或数据库版本记录，且 SHALL NOT 依赖新的 Skill list/read HTTP DTO。KEE sandbox mode 是外部部署前置条件；Java SHALL NOT 配置、修改或绕过它。Skill 内容、发布和回滚由 KEE Git commit 与镜像部署管理。

#### Scenario: KEE 使用已配置 sandbox 发布 Skill
- **WHEN** 联合验收的 KEE 部署已以非 disabled sandbox mode 提供两个预装 Skill
- **THEN** Java 仅依据请求中的精确 `skill_names` 和 SSE `read_skill` 成功事件处理结果
- **THEN** Java 不请求或判断 Skill 版本、哈希或 manifest
