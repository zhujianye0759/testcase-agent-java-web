## Why

Java 当前只校验正式用例引用了合法的需求事实键和证据键，却不校验标题、前置条件、步骤动作和预期结果是否真正由这些正式事实支持。受控任务已经证明模型可把泛称“账号”扩成“用户名/手机号/邮箱”，或自行引入 Token、Session、接口和受保护资源，并仍被当作正式覆盖持久化和交付。

## What Changes

- 在 Java 正式用例业务验收接缝增加读者字段与绑定正式事实/证据正文的失败关闭校验。
- 没有直接正式依据的具体化内容不得作为 `formal` 接收；模型若返回 `pending_confirmation`，必须保留非空缺失信息且不得贡献正式覆盖。
- 持久化、任务详情与 Excel 继续只读取通过业务验收的数据，不在投影或导出阶段掩盖越界内容。
- 不增加全局禁词，不修改 KEE HTTP 请求或返回字段，也不修改既有任务业务数据。

## Capabilities

### New Capabilities

- `formal-testcase-grounding`: 定义正式用例各读者字段必须由其绑定正式事实和证据直接支持的 Java 业务验收规则。

### Modified Capabilities

无。

## Impact

- Java：`FunctionalTestcaseResultValidator`、正式事实的持久化恢复投影、结构化 coordinator 和原子接收测试。
- 数据：不需要迁移；既有完成任务保持只读，新规则只作用于新接收结果。
- KEE：冻结的 Skill 名称、请求字段和结果字段均不变；Java 不信任模型仅凭引用键声明正式依据。
