## Why

Java 当前只校验正式用例引用了合法的需求事实键和证据键，却不校验标题、前置条件、步骤动作和预期结果是否真正由这些正式事实支持。受控任务已经证明模型可把泛称“账号”扩成“用户名/手机号/邮箱”，或自行引入 Token、Session、接口和受保护资源，并仍被当作正式覆盖持久化和交付。

## What Changes

- 在 Java 正式用例业务验收接缝增加读者字段与绑定正式事实/证据正文的失败关闭校验。
- 在 requirement review 原子接收前校验每个正式 requirement fact 叙述字段确由其 cited parsed-unit 原文直接支持，防止未经依据的事实正文继续进入 `formal_supports`。
- 为 `functional-testcase-design` 的业务输入增加只读 `formal_supports`，把当前测试点绑定的完整正式事实字段和证据正文传给 KEE；不改变结果字段、数据库或普通 Agent。
- 没有直接正式依据的具体化内容不得作为 `formal` 接收；模型若返回 `pending_confirmation`，必须保留非空缺失信息且不得贡献正式覆盖。
- 持久化、任务详情与 Excel 继续只读取通过业务验收的数据，不在投影或导出阶段掩盖越界内容。
- 不增加全局禁词，不自创冻结合同之外的 KEE 字段，也不修改既有任务业务数据。
- 按 KEE 冻结合同扩充 `functional-testcase-design` 请求和结果 DTO：支持可选作者/日期、support 级 evidence 闭包，以及高粒度初始化、输入、步骤评价、总体预期、终止、结果采集等字段。
- 扩充 requirement review finding 的可核验根因、影响范围、实际坏例和明确待确认的建议好例；同一任务跨有界调用按根因合并，但评审建议永远不进入正式事实或覆盖。
- 通过增量 Flyway 迁移持久化新增字段，任务详情和恰好两个 Sheet 的 Excel 只读取同一份已验证数据，并隐藏内部键、原始枚举、JSON、Markdown 和凭据。

## Capabilities

### New Capabilities

- `formal-testcase-grounding`: 定义正式用例各读者字段必须由其绑定正式事实和证据直接支持的 Java 业务验收规则。

### Modified Capabilities

无。

## Impact

- Java：`RequirementMaterialReviewValidator`、`FunctionalTestcaseResultValidator`、正式事实的持久化恢复投影、结构化 coordinator 和原子接收测试。
- 数据：增加向后兼容的增量迁移；既有完成任务保持可读，新规则只作用于新接收结果。
- KEE：Skill 名称、isolated-skill 外层路径和执行隔离保持不变；Java 只适配 KEE 已冻结的高粒度 input/result 字段，仍不信任模型仅凭引用键声明正式依据。
