## Why

Java 当前只校验正式用例引用了合法的需求事实键和证据键，却不校验标题、前置条件、步骤动作和预期结果是否真正由这些正式事实支持。受控任务已经证明模型可把泛称“账号”扩成“用户名/手机号/邮箱”，或自行引入 Token、Session、接口和受保护资源，并仍被当作正式覆盖持久化和交付。

## What Changes

- 在 Java 正式用例业务验收接缝增加读者字段与绑定正式事实/证据正文的失败关闭校验。
- 在 requirement review 原子接收前校验每个正式 requirement fact 叙述字段确由其 cited parsed-unit 原文直接支持，防止未经依据的事实正文继续进入 `formal_supports`。
- requirement fact 的直接证据比较忽略 PDF 排版产生的 Unicode 空白，但保留标点且仍限制在单个 cited parsed-unit；坏例 quote 继续保留内部空白边界。
- 为 `functional-testcase-design` 的业务输入增加只读 `formal_supports`，把当前测试点绑定的完整正式事实字段和证据正文传给 KEE；不改变结果字段、数据库或普通 Agent。
- 没有直接正式依据的具体化内容不得作为 `formal` 接收；模型若返回 `pending_confirmation`，必须保留非空缺失信息且不得贡献正式覆盖。
- 持久化、任务详情与 Excel 继续只读取通过业务验收的数据，不在投影或导出阶段掩盖越界内容。
- 不增加全局禁词，不自创冻结合同之外的 KEE 字段，也不修改既有任务业务数据。
- 按 KEE 冻结合同扩充 `functional-testcase-design` 请求和结果 DTO：支持可选作者/日期、support 级 evidence 闭包，以及高粒度初始化、输入、步骤评价、总体预期、终止、结果采集等字段。
- 扩充 requirement review finding 的可核验根因、影响范围、实际坏例和明确待确认的建议好例；同一任务跨有界调用按根因合并，但评审建议永远不进入正式事实或覆盖。
- 通过增量 Flyway 迁移持久化新增字段，任务详情和恰好两个 Sheet 的 Excel 只读取同一份已验证数据，并隐藏内部键、原始枚举、JSON、Markdown 和凭据。
- 允许显式用户恢复一个零写入失败叶子时保留同一拆分树中尚未执行的排队兄弟；仍拒绝多个失败叶子、运行中工作、已接受哈希或任何未完成 work 的部分业务行。
- 将材料评审和功能清单提取从固定 32-unit 输出窗口改为耐久的语义目标窗口；可选相邻 `context_units` 只帮助模型理解边界，不能成为正式 evidence 或重复输出归属。
- 在新增向前迁移中保存目标/context 闭包、材料文档身份和拆分 lineage；新任务按约 8~16 个目标单元规划，旧 work 继续按既有耐久坐标恢复。
- 只有 KEE 明确返回 `response_too_large` 时，Java 才从同一持久化 inventory 原子拆分目标窗口并重新计算只读上下文；结构错误、部分 JSON 和单目标最小容量失败继续失败关闭。
- `extract_function_list` 每个功能项必须携带目标单元中的连续原文 `target_quote`；Java 独立验证 quote 与该项引用目标 evidence 的闭包，并耐久保存跨切片合并后的真实引用。
- 逐材料 isolated-skill 调用只授权当前材料文档；任务冻结范围仍保留全部已选文档，后续逐份遍历、全量核对和测试用例设计不得因传输授权收窄而丢失材料。
- 显式恢复历史功能清单提取窗口时，尚未接受且超过 16 个目标单元的旧窗口必须在首次网络调用前原子二分；新任务语义窗口、已完成兄弟和其他 Skill 不受影响。

## Capabilities

### New Capabilities

- `formal-testcase-grounding`: 定义正式用例各读者字段必须由其绑定正式事实和证据直接支持的 Java 业务验收规则。

### Modified Capabilities

无。

## Impact

- Java：`RequirementMaterialReviewValidator`、`FunctionalTestcaseResultValidator`、正式事实的持久化恢复投影、结构化 coordinator 和原子接收测试。
- Java：增加结构化材料语义窗口规划、`context_units` wire、V17 work 计划坐标和 split/restart 恢复。
- Java：增加 extraction `target_quote` DTO、目标正文校验和 V18 quote 恢复列；context 继续不具备 evidence 所有权。
- Java：材料评审和功能清单提取在调用 KEE 时从任务冻结范围派生当前文档的单文档授权；任务快照、持久窗口与恢复坐标保持不变。
- Java：为 V17 之前的功能清单持久窗口增加排队态原子预拆分；复用既有 `SPLIT` 树和稳定 identity，不增加迁移或模型错误推断。
- 数据：增加向后兼容的增量迁移；既有完成任务保持可读，新规则只作用于新接收结果。
- KEE：Skill 名称、isolated-skill 外层路径和执行隔离保持不变；Java 只适配 KEE 已冻结的高粒度 input/result 字段，仍不信任模型仅凭引用键声明正式依据。
