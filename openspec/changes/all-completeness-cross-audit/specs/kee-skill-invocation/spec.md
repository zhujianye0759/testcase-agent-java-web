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

### Requirement: [REQ-KSI-002] 以 SSE read_skill 成功事件验证指定 Skill 已加载
对每一次请求，系统 SHALL 在既有 SSE `tool_call` 和 `tool_result` 事件中观察与本阶段精确名称相符的 `read_skill` 成功。缺少该事件、名称不符、`read_skill` 失败、工具结果失败、终态错误、流截断或未收到显式完成事件时，Java SHALL 拒绝该次审查或生成结果。

#### Scenario: 模型返回表格但未读取要求的 Skill
- **WHEN** SSE 到达正常完成且 Markdown 表格格式正确，但未观察到精确 Skill 名称的成功 `read_skill`
- **THEN** Java 拒绝结果
- **THEN** 普通 KEE 成功终态不得使审查、批次或任务完成

#### Scenario: read_skill 返回失败
- **WHEN** 精确 Skill 名称的 `read_skill` 事件或结果表示失败
- **THEN** Java 拒绝当前结果
- **THEN** 后续重试仍须重新观察该 Skill 的成功读取

### Requirement: [REQ-KSI-003] 不为 Skill 引入新的调用方状态或部署职责
Java SHALL NOT 校验或存储 Skill version、hash、manifest、outcome 或数据库版本记录，且 SHALL NOT 依赖新的 Skill list/read HTTP DTO。KEE sandbox mode 是外部部署前置条件；Java SHALL NOT 配置、修改或绕过它。Skill 内容、发布和回滚由 KEE Git commit 与镜像部署管理。

#### Scenario: KEE 使用已配置 sandbox 发布 Skill
- **WHEN** 联合验收的 KEE 部署已以非 disabled sandbox mode 提供两个预装 Skill
- **THEN** Java 仅依据请求中的精确 `skill_names` 和 SSE `read_skill` 成功事件处理结果
- **THEN** Java 不请求或判断 Skill 版本、哈希或 manifest
