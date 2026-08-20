## 1. 消费者合同 RED 测试

- [x] 1.1 为 `parsed-units` 补齐分页消费者 RED 测试：默认/显式 limit、续页绑定、稳定排序、全局 ordinal、重复/遗漏/总数变化/游标循环、最终 complete 以及七类显式错误整轮废弃。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=ParsedUnitsWireMockTest test`。 `[Req-ID]: REQ-SKI-001`
- [x] 1.2 为同步 `isolated-skill` 建立精确六字段请求 RED 测试，证明单 KB/单 scope/唯一版本/非空项目/文档集合相等，并证明未知聊天字段、超过 2 MiB 和普通 Agent 回退被拒绝。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillWireMockTest test`。 `[Req-ID]: REQ-SKI-002, REQ-SKI-005`
- [x] 1.3 为三类 input 建立 DTO/序列化 RED 测试，覆盖所有字段、枚举、计数和唯一性；材料切片必须证明 `33..64` 合法、断序非法且不得重编号。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillInputContractTest test`。 `[Req-ID]: REQ-SKI-003`
- [x] 1.4 为统一成功/失败外壳及三类 result 建立反序列化 RED 测试，拒绝未知字段、错误 schema/skill、SSE/Markdown、超过 4 MiB、错误数组边界和不连续 step_no。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=StructuredSkillResultContractTest test`。 `[Req-ID]: REQ-SKI-004, REQ-SKI-005`
- [x] 1.5 为 KEE 冻结提交 `4c68f2f8` 中 `feature-scope-reconciliation` 的 `extract_function_list`/`reconcile` 严格 operation 建立并转绿增补合同：证明功能清单切片保留全局 ordinal、提取结果无 item_key 且 evidence 非空唯一、reconcile 保留既有输入/结果，并拒绝 input/result operation 错配。验证：`./backend/mvnw.cmd -f backend/pom.xml -Dtest=FeatureScopeOperationContractTest test`。 `[Req-ID]: REQ-SKI-003, REQ-SKI-004, REQ-STG-003`

## 2. 窄端口与 HTTP 适配器

- [x] 2.1 将材料读取能力收敛为 `ParsedUnitCatalogPort`，保持当前只读分页实现并使整轮结果在最终门禁前不可用；不得修改 KEE 或引入原始文档解析。验证：任务 1.1 GREEN 及现有 parsed-units 聚焦测试。 `[Req-ID]: REQ-SKI-001`
- [x] 2.2 新建 `StructuredSkillExecutionPort`、三个明确调用类型、三个明确结果类型及公共 envelope；不得扩展旧 Chat DTO 或增加第三方 JSON/Agent 依赖。验证：任务 1.3、1.4 GREEN。 `[Req-ID]: REQ-SKI-002~005`
- [x] 2.3 在 WebClient 适配器实现现有专用路径的同步 JSON 调用、2/4 MiB 边界、稳定错误分类和超时；删除该专用调用对准备会话、SSE、工具事件和 Markdown 解析的依赖，但普通 Agent Chat 调用保持不变。验证：`WebClientKnowledgeAgentAdapterTest,StructuredSkillWireMockTest`。 `[Req-ID]: REQ-SKI-002, REQ-SKI-004, REQ-SKI-005`
- [x] 2.4 增加范围构造器，完全由冻结 `RequirementScope` 生成单 KB/系统/版本/项目/文档白名单，并在网络前拒绝不一致范围。验证：新增 `StructuredSkillScopeTest` 及 `ScopePolicyTest`。 `[Req-ID]: REQ-SKI-002`

## 3. 键、证据和业务 validator

- [x] 3.1 建立任务内稳定 key/evidence 注册表及作用域测试，覆盖材料、单元、功能清单条目、需求事实、发现、核对、功能、测试点和用例；拒绝跨任务、跨材料、ExampleScope、未遍历或退休证据。 `[Req-ID]: REQ-STG-001`
- [x] 3.2 实现需求材料审查 validator：两个数组分别 0..200、合计至少 1，验证引用和 handling_level，并限制 `prototype`/`requirement_list` 只能作为补充证据。先运行失败夹具，再使 `RequirementMaterialReviewValidatorTest` GREEN。 `[Req-ID]: REQ-STG-001, REQ-STG-002`
- [x] 3.3 实现功能提取与核对 validator：提取结果只接受当前材料/切片 evidence，允许 0 项，Java 使用 UTF-8 长度前缀字段编码生成无 NUL 边界歧义的 stable item key 并跨片去重；核对结果验证全部来源 key/evidence 和终态，保留 pending/insufficient/conflict/split/merge/duplicate。验证：`FunctionListExtractionValidatorTest,FeatureReconciliationValidatorTest`。 `[Req-ID]: REQ-STG-001, REQ-STG-003`
- [x] 3.4 实现测试点和用例 validator：精确回显 function/test-point key、引用闭包、七类 type、formal/general-experience 状态映射；formal 用例两类引用非空，任何 pending 用例 missing information 非空；正式覆盖及 1..50 非固定数量用例。验证：`FunctionalTestcaseResultValidatorTest`。 `[Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005`
- [x] 3.5 在任务创建前冻结非空 project，要求 `function_list` 加至少一种正式需求材料，并基于文件 SHA-256 拒绝缺失或重复材料；原型和需求清单不得替代正式材料。验证：`WebClientKnowledgeScopeCatalogAdapterTest,DynamicScopeCatalogServiceTest,DynamicTaskScopeResolverTest`。 `[Req-ID]: REQ-STG-008, REQ-SKI-002`

## 4. 耐久状态与原子接收

- [x] 4.1 设计并添加最小 Flyway 迁移，分别持久化材料审查事实/发现、功能清单条目、核对关系、测试点、结构化用例、引用绑定、处理状态和覆盖结果；引用绑定的 `subject_type` 必须进入主键/查询，不得复用 KEE 数据库或保存原始模型 JSON/Markdown。验证：Testcontainers 迁移测试。 `[Req-ID]: REQ-STG-001~007`
- [x] 4.2 为四类工作项实现事务性接收：validator、业务行、带 subject type 的引用和尝试终态全成功才提交，任何异常回滚全部写入；accept/fail 在锁事务内以数据库冻结的 skill/operation/material/slice/owner/lease 为授权真相，新 stable item key 仅在提交成功后发布。所有可引用业务 key 以 `(task_id,key)` 保证耐久唯一；功能清单跨切片相同文本采用原子 upsert 后锁读并合并授权 evidence，模型 key 冲突和稳定文本冲突均回滚。验证：伪造 claim、跨材料/切片、跨片重复与重启 registry、并发首次稳定 key 写入、模型 key 冲突、operation 错配、中途回滚和 registry 可见性 MySQL 测试。 `[Req-ID]: REQ-STG-006`
- [x] 4.3 实现稳定切片/工作项幂等身份、有界可重试白名单和租约恢复，保留 parsed-units 全局 ordinal；只允许 `model_unavailable|model_execution_failed` 最多再领一次，生产 coordinator 必须按同一 work ID 定向领取，`structured_output_invalid` 和其他非白名单失败终止且不回退 Markdown；failure type 严格枚举。过期 attempt 失效，达到最大尝试数不再领取；同 task+identity 并发注册返回同一 ID，但相同 identity 的任一冻结载荷坐标不一致必须拒绝且不修改旧行。验证：并发注册/认领、不同 operation/evidence closure 重放、未知 failure type、两类短暂错误同 work 重试、结构无效不重试、过期恢复、stale claim 和尝试上限测试。 `[Req-ID]: REQ-SKI-003, REQ-STG-006`

## 5. 结构化生成工作流与完成门禁

- [x] 5.1 将材料完整遍历后的单元按 1..32 连续全局 ordinal 切片，逐材料执行 `requirement-material-quality-review`，所有切片终态后才进入核对阶段。验证：`GenerationWorkflowStructuredReviewTest`。 `[Req-ID]: REQ-SKI-001, REQ-SKI-003, REQ-STG-002`
- [x] 5.2 先对功能清单 parsed units 按 1..32 全局连续切片调用 `feature-scope-reconciliation(operation=extract_function_list)`，校验证据后由 Java 生成稳定 item_key 并跨片聚合/去重；再以已验证功能清单条目和需求事实调用同一 Skill 的 `operation=reconcile`，全部来源终态后原子冻结最终功能集合。阶段三必须从数据库 confirmed mapping 恢复最终功能身份和事实引用，registry 以幂等 require-or-register 重建；不得按 fact.function 猜功能或直接传递内存结果。取消旧 Markdown bulk/singleton 补偿对新路径的影响，不猜 Excel 层级。验证：生产 coordinator、真实 Store 重启与重复执行测试。 `[Req-ID]: REQ-STG-003`
- [x] 5.3 由 Java 根据正式事实的每个非空规则值建立稳定、非固定数量测试点并逐点调用 `functional-testcase-design`；stable key 使用 UTF-8 长度前缀字段编码，不得静默丢弃多值，移除固定正反两条和 `2N` 作为新路径完成条件。验证：`GenerationWorkflowStructuredTestcaseTest`。 `[Req-ID]: REQ-STG-004, REQ-STG-005`
- [x] 5.4 分离处理状态与覆盖结果并统一 DB/wire enum：processing=`PENDING|RUNNING|COMPLETED|FAILED|CANCELLED`，coverage=`PENDING|COMPLETE|PARTIAL|UNABLE_TO_GENERATE`；全部计划工作无执行失败地终态即为 `COMPLETED`，覆盖不足独立标记，`PARTIAL` 不得出现在 structured processing 轴。遍历和各阶段边界取消必须停止后续 KEE/导出并持久化 CANCELLED；启动恢复必须重排无 legacy batch 的 structured AUDITING/GENERATING/VALIDATING、释放 slot、失效旧 RUNNING attempt，同时保留 COMPLETED work。验证：状态机、取消、启动恢复、持久化与页面测试。 `[Req-ID]: REQ-STG-007, REQ-SGD-002`

## 6. 页面与固定双 Sheet 交付

- [x] 6.1 将任务详情 DTO 改为已持久化的结构化审查、核对、测试点、用例、处理状态和覆盖投影；禁止原始 JSON、Markdown、内部 key/凭据/URL/栈进入响应。验证：`GenerationTaskDetailTest`。 `[Req-ID]: REQ-SGD-001, REQ-SGD-002`
- [x] 6.2 更新 PC 任务详情页面及状态测试，分别展示持久化材料遍历/需求审查/功能核对/用例设计进度、正式覆盖和待确认候选；structured ALL 禁止借用 legacy batch/businessProgress，`COMPLETED+PARTIAL` 保持顶层完成且文案明确覆盖部分完整；业务枚举未知值不得原样回显，取消只完成实际已完成阶段。保留 loading/ready/empty/no-results/error/forbidden/not-found、键盘焦点和过期响应保护。验证：`npm --prefix frontend run test -- --run TaskDetailView.spec.ts`，并在 1440x820 及相关宽度执行视觉检查。 `[Req-ID]: REQ-SGD-001, REQ-SGD-002`
- [x] 6.3 重构 Excel 输入为与页面相同的已持久化结构化投影，仍恰好生成“需求与功能清单审查发现”“测试用例”两个 Sheet，区分 formal/pending_confirmation 并保持公式安全、哈希和回读；零功能/零用例时仍生成并回读两个表头 Sheet，完成 API 拒绝 null artifact；重复 stable source ID 必须失败关闭，不得静默少行。验证：`StructuredWorkbookExporterTest`。 `[Req-ID]: REQ-SGD-003, REQ-SGD-004`

## 7. 迁移、兼容回归与外部验收

- [x] 7.1 清点并隔离旧 `all-completeness-cross-audit` 中被替代的 SSE、Markdown、准备会话、固定重试提示和 `2N` 需求/任务；保留与新路线兼容的 parsed-units、任务耐久性、取消/重试和双 Sheet 能力，未经用户确认不归档旧 change。证据：`evidence/7.1-7.2-local-gates.md`。 `[Req-ID]: all`
- [x] 7.2 运行后端聚焦测试、MySQL/Testcontainers 回归、前端 typecheck/lint/test/build、`git diff --check` 和 `openspec validate adapt-structured-kee-contracts --strict`；不得把 fixture PASS 表述为真实 KEE 联调。证据：`evidence/7.1-7.2-local-gates.md`。 `[Req-ID]: all`
- [ ] 7.3 在 KEE `4c68f2f8`（前置 `ba21fecf`）对应 latest 获得部署通知和精确镜像证据后，执行 parsed-units 全遍历、三个 Skill（含双 operation）、十类错误、一次 repair 标志、同步响应及普通 Agent 非干扰联合验收；记录提交/镜像和运行证据。部署条件不足时保持本任务未完成。 `[Req-ID]: REQ-SKI-001~006, REQ-SGD-004`
