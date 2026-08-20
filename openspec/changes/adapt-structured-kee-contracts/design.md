## Context

Java Web 当前通过 `WebClientKnowledgeAgentAdapter` 复用普通 Agent Chat 形状，依赖准备会话、SSE 工具证据和 Markdown parser。活动变更 `all-completeness-cross-audit` 还把每个冻结功能固定为正向/反向两条并以 `2N` 作为完成门禁。KEE 最新路线保留同一 `isolated-skill` 路径，但将其改为同步结构化 JSON、服务器确定性加载一个 Skill、直接非流式调用 Agent 配置模型并严格校验结构；普通 AgentEngine 与知识链路不变。

KEE 的 `add-parsed-document-structure-api` 已本地归档但未 push/merge；`add-structured-isolated-skill-execution` 已由主线程完成代码审查和聚焦测试，冻结本地提交为 `4c68f2f8`（前置实现 `ba21fecf`），但尚未部署或推送。因此 Java 可以实现消费者和业务 finalizer（最终业务校验者），但不得把外部接口描述为已部署，真实 E2E 仍是后续门禁。

## Goals / Non-Goals

**Goals:**

- 以独立端口消费冻结的 `parsed-units` 和同步结构化 `isolated-skill`，不再把模型 Markdown 当机器合同。
- 在 Java 内确定性验证调用范围、所有键引用、证据归属、终态、正式覆盖、状态映射和持久化规则。
- 保留持久化解析文本的完整遍历证据，同时如实限制其对原生 PDF/Word/Excel 结构的证明范围。
- 支持由需求事实和测试点决定的非固定数量用例，区分正式覆盖与待确认经验候选。
- 只把已验证、原子保存的数据投影到页面和固定两个 Excel Sheet。

**Non-Goals:**

- 不修改 KEE，不新增 HTTP 路径，不设计 `/skill-invocations/structured`。
- 不恢复 SSE、Markdown、`read_skill` 工具证据、AgentEngine 或调用方提供 prompt/schema/model/tool 字段。
- 不在 Java 中解析原始 PDF、Word、Excel、OCR、图片或建设原生结构库。
- 不建设 Markdown 预览、原始 JSON 查看器、审批流、登录权限 UI 或性能/安全专项用例生成。
- 不声称 KEE 代码已经验收、合并、推送或部署。

## Decisions

### 1. 将材料枚举与 Skill 执行拆成两个窄端口

`ParsedUnitCatalogPort` 只负责 `GET /api/v1/knowledge/{knowledgeId}/parsed-units` 的逐页读取和服务合同解码；`StructuredSkillExecutionPort` 只负责现有 `POST /api/v1/agent-chat/{sessionId}/isolated-skill` 的六字段同步 JSON 调用。两个端口都由工作流核心调用，HTTP、鉴权头和 JSON 是适配器细节。

结构化 Skill 端口使用三个明确的调用类型和三个明确的结果类型，共用一个成功/失败外壳；不在旧 `KnowledgeAgentInvocation` 上继续堆叠可空字段，也不复用普通 Chat 请求 DTO。这样可在编译期阻止 query、system prompt、模型覆盖、Web/MCP、history/Memory、附件和图片等聊天字段进入专用调用。

备选方案：在现有 Chat DTO 上删字段后复用。拒绝，因为当前 DTO、会话准备和 SSE 解析与普通 AgentEngine 行为耦合，容易在迁移中留下隐式回退。

### 2. Java 先冻结并验证唯一业务范围，再构造六字段请求

调用只从任务已冻结的 `RequirementScope` 构造：一个 `agent_id`、一个 KB、1..100 个唯一文档、一个系统/唯一版本/非空项目 scope，以及与外层完全相等的文档集合。Skill 名称由工作流阶段枚举决定，不接收页面或模型自由文本。

冻结前先从 KEE 文档元数据读取文件 SHA-256，并要求任务内全部文件哈希存在且唯一。任务必须同时包含 `function_list` 和至少一种正式需求材料（`requirements_spec` 或 `work_order_plan`）；原型与需求清单只能补充，不能替代正式材料。同哈希文件即使名称或路径不同也按重复内容拒绝，避免重复材料增加证据权重。

Java 在发出请求前执行相同的本地一致性检查；KEE 的授权和结构校验仍是独立安全边界。Java 不把业务 `input` 当作授权证据，也不因 `forbidden`、`unsupported_skill` 等错误尝试普通 Agent Chat 回退。

### 3. 材料完整遍历与 Skill 的 32 单元切片是两个不同门禁

Java 必须先完整读取一个文档的全部 parsed units，验证全局唯一 `unit_id`、稳定 `(chunk_index, unit_id)` 顺序、全局连续 `ordinal=1..total_units`、无游标循环、每页总数一致，以及最终 `complete=true` 且 `next_cursor=null`。任一页失败时丢弃本轮全部暂存结果。

材料审查 Skill 每次只接收 1..32 个连续单元。切片保留 parsed-units 的全局 ordinal：首值只需 `>=1`，后续严格等于前一项加 1；例如 `33..64` 合法，禁止重编号为 `1..32`。工作流以耐久工作项覆盖全部已验收单元，并在所有切片取得业务终态后才能完成材料审查。

备选方案：边读页边把部分单元发送给模型并持久化结果。拒绝，因为后页失败会使先前模型结果失去“完整材料遍历”前提。

### 4. 建立 Java 所有的键与证据注册表

Java 为材料、单元、功能清单条目、需求事实、审查发现、核对关系、功能、测试点和用例分配任务内稳定 `*_key`。`evidence_key` 解析到任务冻结范围内的证据记录；模型返回的每个引用必须存在于当前调用允许集合，且不得把 ExampleScope、其他任务、其他材料或未遍历单元作为正式证据。

`requirements_spec` 和 `work_order_plan` 可以形成正式需求事实；`prototype` 和 `requirement_list` 只作为补充审查材料，不能单独支撑正式需求事实、正式覆盖或正式缺陷结论。输入重复内容不增强可信度。

KEE 已验证字段、类型、枚举、边界和编号，不等于业务可接受。Java 反序列化后仍执行引用和业务不变量验证，只有整个工作项通过才在一个事务中接收结果；失败结果、部分数组和修复前结果均不入库。

### 5. 三阶段工作流以结构化终态收敛

阶段一按正式需求与补充材料切片调用 `requirement-material-quality-review`。Java 要求返回的 `requirement_facts` 与 `review_findings` 分别在 0..200 且合计至少一项，并验证所有证据引用与材料角色。功能清单切片不冒充需求材料，而是调用同一 `feature-scope-reconciliation` Skill 的 `operation=extract_function_list`；模型只返回 path、description 和当前切片 evidence keys，Java 校验后生成稳定任务内 item key，并跨切片聚合、去重。

阶段二调用同一 Skill 的 `operation=reconcile`。Java 验证 input/result operation 匹配，并验证每个输入功能清单条目和需求事实都进入可追溯终态；`confirmed` 与 `pending_confirmation` 分开保存，`blocking` 材料问题和证据不足不能被静默提升为已确认范围。该双操作设计不增加第四个 Skill，不复用普通 Agent/Markdown，也不要求 Java 理解 Excel 原生层级。核对提交后，阶段三只从数据库读取 confirmed reconciliation 及其 function-list item / requirement-fact 引用来恢复最终功能身份；不得仅按事实的 function 显示文本分组，也不得把刚收到但尚未提交的内存结果直接传给下一阶段。registry 对这些已提交 key 使用幂等 require-or-register，使当前进程的提交后发布与新进程重建走同一路径。

阶段三由 Java 基于正式需求事实和已确认范围形成数量不固定的测试点，并逐测试点调用 `functional-testcase-design`。返回的 `function_key`、`test_point_key` 必须与请求完全一致。`formal_requirement` 测试点至少接收一条 `formal` 用例；`general_experience` 只能接收 `pending_confirmation` 用例，且不计入正式覆盖。每个结果允许 1..50 条用例，不再要求正反成对。

### 6. 处理状态和覆盖结果使用独立状态轴

处理状态记录工作是否排队、执行、重试、失败、取消或完成；覆盖结果记录正式需求事实、测试点和正式用例是否满足覆盖门禁。`COMPLETED` 只表示所有必需工作已取得终态并通过业务校验；正式覆盖不足时不得伪装为覆盖完成。`PARTIAL` 不能升级为 `COMPLETED`。

结构化错误按 `error.details.type` 分类。Java 只把 `model_unavailable`、`model_execution_failed` 视为短暂模型依赖错误，并最多追加一次 Java 级尝试；生产 coordinator 在首轮失败提交后按原 `work_item_id` 定向领取，避免全局 next 查询误领其他 queued work。`structured_output_invalid` 已穷尽 KEE 调用内的一次格式修复，必须终止而不得形成第三次模型调用。`failure_type` 只允许固定白名单，不写异常原文。任何失败都产生零业务结果，KEE 的 `repair_attempted` 仅作为诊断元数据，不改变 Java 的接收规则。

工作项 `identity_key` 是幂等定位键，不是绕过冻结坐标比较的授权凭据。原子 upsert 后必须锁定读取已有行，并逐项比较 skill、operation、ordinal、material、source label、evidence closure、function key 和 test-point key；只有完全相同的重放才返回原 ID，不同载荷不得修改原行。

进程内 registry 只用于当前校验闭包，不承担耐久唯一性。所有跨 work item 可引用的业务表都保存 `task_id` 并建立 `(task_id,business_key)` 唯一约束；重复模型 key 的第二次写入作为任务内身份冲突回滚。功能清单 stable key 由 Java 生成，允许跨切片合并：先执行原子 insert-on-duplicate，再锁读已存在行，核对 path/description 一致后把当前切片的授权 evidence binding 合并到唯一 owner work；冲突则回滚当前接收。详情和 Excel 均从这些任务级唯一表读取。

### 7. 页面和 Excel 只消费持久化业务投影

任务详情从 Java 数据库读取材料审查发现、功能核对、测试点、用例、处理状态和覆盖结果。它不代理或保存供页面展示的 KEE 原始 JSON、修复原文、Skill 正文或 Markdown。生产配置强制注入 structured ALL coordinator；新 ALL 任务没有 nullable coordinator 或旧 Markdown 回退。

Excel 仍恰好两个 Sheet：“需求与功能清单审查发现”和“测试用例”。导出器从已验证记录确定性生成文件，保留公式注入防护、来源去重、哈希和回读校验。待确认经验用例必须清晰标识，且汇总统计不得把它计入正式覆盖。零功能或零用例也是合法终态时，导出器仍生成两个仅含固定表头的 Sheet 并回读；任务完成 API 拒绝 null artifact。

结构化 coordinator 在遍历、切片工作、核对、测试点和导出边界检查用户取消。取消单独写入 `processing=CANCELLED`，覆盖在未完成时保持 `PENDING`，不进入业务失败分类、不调用后续 KEE，也不发布 artifact。structured ALL 在模型阶段保持应用任务 `AUDITING`，到最终导出门禁才进入 `GENERATING/VALIDATING`，使现有任务队列能够在进程重启后重新领取。启动恢复还显式处理无 legacy batch 的 structured `AUDITING/GENERATING/VALIDATING`：释放 slot，将旧 RUNNING attempt 固定记为 `model_execution_failed` 并受两次上限约束，保留所有 COMPLETED work 和已验收业务行。

## Risks / Trade-offs

- [KEE 代码已验收但尚未部署] → 以冻结提交 `4c68f2f8`（前置 `ba21fecf`）完成本地消费者合同、业务校验和 fixture 集成；真实集成、可运行和发布结论必须等待 KEE latest 部署通知及精确镜像证据。
- [现有活动变更仍描述 SSE、Markdown 和 `2N`] → 本 change 明确替代这些条款；实施前先做需求 ID 和任务迁移，不在当前脏工作树覆盖旧文件。
- [32 单元切片可能在任务重试时重复处理] → 使用任务内稳定切片身份、全局 ordinal 范围和原子接收；同一切片重试替换失败尝试，不重复累计业务结果。
- [模型结果结构合法但引用错误] → Java 键注册表、证据归属和终态/覆盖验证失败关闭，模型结果不直接入库。
- [同步响应接近 4 MiB] → WebClient 采用明确上限并拒绝截断；任务按固定输入边界拆分，不增加无界内存聚合。
- [普通 Agent 路径回归] → 新端口不依赖 AgentEngine/SSE/工具事件；只对有实际调用边的普通路径执行兼容回归。

## Migration Plan

1. 先以本 change 的消费者合同测试冻结六字段请求、三类输入/结果、成功/失败外壳和 parsed-units 遍历规则，生产代码保持不变。
2. 以 KEE 冻结提交 `4c68f2f8`（前置 `ba21fecf`）实现纯 Java DTO、业务 validator、端口和持久化迁移；外部适配器只使用 WireMock/固定 fixture，不声称联调完成。
3. 用新结构化端口替换专用路径上的准备会话、SSE 和 Markdown 接收；普通 Agent Chat 端口保持原样。
4. 迁移工作流、状态/覆盖模型、页面投影和双 Sheet 导出；在新完成门禁通过前不发布新制品。
5. KEE latest 完成部署并提供精确镜像证据后，执行真实 parsed-units 全遍历、三个 Skill、错误和普通 Agent 非干扰联合验收。
6. 回滚时保留数据库迁移的向前兼容读取，切回上一 Java 制品；不得把新结构化记录降级解析为旧 Markdown 或恢复 `2N` 假完成门禁。

## Open Questions

无字段问题。`extract_function_list`/`reconcile` 已冻结于 KEE 本地提交 `4c68f2f8`；唯一外部门禁是该提交尚未部署，真实 E2E 必须等待 KEE latest 部署通知与精确镜像证据。
