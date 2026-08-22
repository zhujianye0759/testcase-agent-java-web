## 1. 消费者合同 RED 测试

- [ ] 1.1 为 `parsed-units` 补齐分页消费者 RED 测试：默认/显式 limit、续页绑定、稳定排序、全局 ordinal、重复/遗漏/总数变化/游标循环、最终 complete 以及七类显式错误整轮废弃。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SKI-001`
- [ ] 1.2 为同步 `isolated-skill` 建立精确六字段请求 RED 测试，证明单 KB/单 scope/唯一版本/非空项目/文档集合相等，并证明未知聊天字段、超过 2 MiB 和普通 Agent 回退被拒绝。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillWireMockTest test`。 `[Req-ID]: REQ-SKI-002, REQ-SKI-005`
- [ ] 1.3 为三类 input 建立 DTO/序列化 RED 测试，覆盖所有字段、枚举、计数和唯一性；材料切片必须证明 `33..64` 合法、断序非法且不得重编号。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillInputContractTest test`。 `[Req-ID]: REQ-SKI-003`
- [ ] 1.4 为统一成功/失败外壳及三类 result 建立反序列化 RED 测试，拒绝未知字段、错误 schema/skill、SSE/Markdown、超过 4 MiB、错误数组边界和不连续 step_no。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillResultContractTest test`。 `[Req-ID]: REQ-SKI-004, REQ-SKI-005`

## 2. 窄端口与 HTTP 适配器

- [ ] 2.1 将材料读取能力收敛为 `ParsedUnitCatalogPort`，保持当前只读分页实现并使整轮结果在最终门禁前不可用；不得修改 KEE 或引入原始文档解析。验证：任务 1.1 GREEN 及现有 parsed-units 聚焦测试。 `[Req-ID]: REQ-SKI-001`
- [ ] 2.2 新建 `StructuredSkillExecutionPort`、三个明确调用类型、三个明确结果类型及公共 envelope；不得扩展旧 Chat DTO 或增加第三方 JSON/Agent 依赖。验证：任务 1.3、1.4 GREEN。 `[Req-ID]: REQ-SKI-002~005`
- [ ] 2.3 在 WebClient 适配器实现现有专用路径的同步 JSON 调用、2/4 MiB 边界、稳定错误分类和超时；删除该专用调用对准备会话、SSE、工具事件和 Markdown 解析的依赖，但普通 Agent Chat 调用保持不变。验证：`WebClientKnowledgeAgentAdapterTest,StructuredSkillWireMockTest`。 `[Req-ID]: REQ-SKI-002, REQ-SKI-004, REQ-SKI-005`
- [ ] 2.4 增加范围构造器，完全由冻结 `RequirementScope` 生成单 KB/系统/版本/项目/文档白名单，并在网络前拒绝不一致范围。验证：新增 `StructuredSkillScopeTest` 及 `ScopePolicyTest`。 `[Req-ID]: REQ-SKI-002`

## 3. 键、证据和业务 validator

- [ ] 3.1 建立任务内稳定 key/evidence 注册表及作用域测试，覆盖材料、单元、功能清单条目、需求事实、发现、核对、功能、测试点和用例；拒绝跨任务、跨材料、ExampleScope、未遍历或退休证据。 `[Req-ID]: REQ-STG-001`
- [ ] 3.2 实现需求材料审查 validator：两个数组分别 0..200、合计至少 1，验证引用和 handling_level，并限制 `prototype`/`requirement_list` 只能作为补充证据。先运行失败夹具，再使 `RequirementMaterialReviewValidatorTest` GREEN。 `[Req-ID]: REQ-STG-001, REQ-STG-002`
- [ ] 3.3 实现功能核对 validator：验证全部来源 key 和 evidence，保证每个输入来源取得终态，保留 pending/insufficient/conflict/split/merge/duplicate，不自动提升或改写。验证：`FeatureReconciliationValidatorTest`。 `[Req-ID]: REQ-STG-001, REQ-STG-003`
- [ ] 3.4 实现测试点和用例 validator：精确回显 function/test-point key、引用闭包、七类 type、formal/general-experience 状态映射、正式覆盖及 1..50 非固定数量用例。验证：`FunctionalTestcaseResultValidatorTest`。 `[Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005`

## 4. 耐久状态与原子接收

- [ ] 4.1 设计并添加最小 Flyway 迁移，分别持久化材料审查事实/发现、核对关系、测试点、结构化用例、引用绑定、处理状态和覆盖结果；不得复用 KEE 数据库或保存原始模型 JSON/Markdown。验证：Testcontainers 迁移测试。 `[Req-ID]: REQ-STG-001~007`
- [ ] 4.2 为三个工作项实现事务性接收：validator、业务行、引用和尝试终态全成功才提交，任何异常回滚全部写入。验证：新增 MySQL 原子接收集成测试。 `[Req-ID]: REQ-STG-006`
- [ ] 4.3 实现稳定切片/工作项幂等身份和失败重试，保留 parsed-units 全局 ordinal；只替换当前失败尝试，不覆盖其他已验收结果。验证：重复接收、重试替换、并发认领和中途回滚测试。 `[Req-ID]: REQ-SKI-003, REQ-STG-006`

## 5. 结构化生成工作流与完成门禁

- [ ] 5.1 将材料完整遍历后的单元按 1..32 连续全局 ordinal 切片，逐材料执行 `requirement-material-quality-review`，所有切片终态后才进入核对阶段。验证：`GenerationWorkflowStructuredReviewTest`。 `[Req-ID]: REQ-SKI-001, REQ-SKI-003, REQ-STG-002`
- [ ] 5.2 以已验证功能清单条目和需求事实调用 `feature-scope-reconciliation`，全部来源终态后原子冻结最终功能集合；取消旧 Markdown bulk/singleton 补偿对新路径的影响。验证：`GenerationWorkflowStructuredReconciliationTest`。 `[Req-ID]: REQ-STG-003`
- [ ] 5.3 由 Java 根据正式事实建立非固定数量测试点并逐点调用 `functional-testcase-design`；移除固定正反两条和 `2N` 作为新路径完成条件。验证：`GenerationWorkflowStructuredTestcaseTest`。 `[Req-ID]: REQ-STG-004, REQ-STG-005`
- [ ] 5.4 分离处理状态与覆盖结果，只有所有材料/核对/正式测试点/用例/制品门禁通过才进入 `COMPLETED`；部分成功保持 `PARTIAL`，取消和失败不得发布完整制品。验证：状态机与恢复测试。 `[Req-ID]: REQ-STG-007`

## 6. 页面与固定双 Sheet 交付

- [ ] 6.1 将任务详情 DTO 改为已持久化的结构化审查、核对、测试点、用例、处理状态和覆盖投影；禁止原始 JSON、Markdown、内部 key/凭据/URL/栈进入响应。验证：`GenerationTaskDetailTest`。 `[Req-ID]: REQ-SGD-001, REQ-SGD-002`
- [ ] 6.2 更新 PC 任务详情页面及状态测试，分别展示处理进度、正式覆盖和待确认候选，保留 loading/ready/empty/no-results/error/forbidden/not-found、键盘焦点和过期响应保护。验证：`npm --prefix frontend run test -- --run TaskDetailView.spec.ts`，并在 1440x820 及相关宽度执行视觉检查。 `[Req-ID]: REQ-SGD-001, REQ-SGD-002`
- [ ] 6.3 重构 Excel 输入为结构化记录，仍恰好生成“需求与功能清单审查发现”“测试用例”两个 Sheet，区分 formal/pending_confirmation 并保持公式安全、去重、哈希和回读。验证：`MarkdownWorkbookExporterTest` 的结构化替代测试。 `[Req-ID]: REQ-SGD-003, REQ-SGD-004`

## 7. 迁移、兼容回归与外部验收

- [ ] 7.1 清点并隔离旧 `all-completeness-cross-audit` 中被替代的 SSE、Markdown、准备会话、固定重试提示和 `2N` 需求/任务；保留与新路线兼容的 parsed-units、任务耐久性、取消/重试和双 Sheet 能力，未经用户确认不归档旧 change。 `[Req-ID]: all`
- [ ] 7.2 运行后端聚焦测试、MySQL/Testcontainers 回归、前端 typecheck/lint/test/build、`git diff --check` 和 `openspec validate adapt-structured-kee-contracts --strict`；不得把 fixture PASS 表述为真实 KEE 联调。 `[Req-ID]: all`
- [ ] 7.3 在 KEE `ba21fecf` 对应 latest 获得部署通知和精确镜像证据后，执行 parsed-units 全遍历、三个 Skill、十类错误、一次 repair 标志、同步响应及普通 Agent 非干扰联合验收；记录提交/镜像和运行证据。部署条件不足时保持本任务未完成。 `[Req-ID]: REQ-SKI-001~006, REQ-SGD-004`
