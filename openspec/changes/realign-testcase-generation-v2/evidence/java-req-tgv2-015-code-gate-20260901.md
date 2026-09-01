# Java V2 用例身份标签与窄恢复代码门禁（2026-09-01）

## 结论

`REQ-TGV2-015` 已完成代码、真实 MySQL、前端和静态门禁。用例名称/标题只能由当前功能名称、功能路径末级、当前测试点，或单一正式事实/引文中的连续短语支撑；正式候选不得借待确认测试点作为身份依据，组合标签最多使用一个完整语义片段。其他执行字段仍须逐字段受正式事实或引文支撑。恢复入口仅识别精确的 V2 用例设计零写入失败状态，不包含任务、项目、知识库、材料名称、页数或固定业务数量特判。

本文件生成时尚未部署，也未发送业务 retry；部署身份和 live 零副作用结果将在部署门禁完成后补充。

## TDD 证据

- 首轮后端全量暴露旧测试夹具身份文字与新身份规则不一致，以及 expected-results 恢复未约束覆盖状态；这些问题均按生产规则和真实恢复边界修复，没有删除测试或放宽业务断言。
- NUL 分隔符跨事实拼接、当前范围被通用包装词误拒、恢复 work 已写 coverage、包装后 generic-only 标签均由新增失败测试先证明旧实现错误，再完成最小修复。
- 最后一轮新增两层重复包装反例，RED 为 `FunctionalTestcaseV2ValidatorTest` 32 项中 2 项失败；改为固定点剥离通用包装前后缀后 GREEN 32/32。
- 最终实现复审发现字符串固定点会物化左右包装组合。新增 250 层前缀与 250 层后缀的公开 validator 边界测试，旧实现在 500ms 内超时且堆栈停在变体 `HashSet`；改为可达左右索引后不再复制笛卡尔积字符串并通过。首次实现编译时曾出现普通 lambda 捕获错误，已单独修正，不作为业务 RED。
- 标准/实现轴复审随后指出：仅改为边界索引仍可能逐个比较大量共享长前缀的 terminal slice，CPU 最坏复杂度仍偏高。最终实现只保留同一起点的最短非空 terminal slice（较长且被包含的 slice 必然由此前缀蕴含），并用预计算失败表的 KMP 做线性包含匹配；新增长 unsupported core、100 条事实和 1000 个重叠后缀的公开 validator 回归。该项是静态复杂度发现后的防回归测试，最终 500ms 门槛通过，不虚构为旧实现已触发的 RED。
- 最终标准复审还发现 exact composition 会为每条语义来源遍历标签的全部字符位置，即使包装边界极稀疏。新增 15,000 字符、100 条事实、连续 40 次公共校验的资源边界，旧实现于 500ms 超时且堆栈停在 `hasReachableIdentityComposition`；边界对象改为只遍历实际可达起止索引后 GREEN。
- 首次部署的数据库全等门禁证明线上旧任务使用“`result_snapshot` 为空且 task 保存安全 `name` 误拒诊断”的历史终态外壳，而原测试夹具只覆盖“有快照、无 task 诊断”，因此 `canRetry` 仍为 false。新增真实外壳 RED 后，恢复门禁仅增加两种精确历史外壳的兼容：task 安全诊断必须与至少一个失败 work 的最新诊断完全一致；快照和诊断混合、部分诊断或不一致仍失败关闭。实现复审又发现旧快照用例只验证资格，没有执行 mutation；端到端测试随即 RED，证明 MySQL JSON 列与字符串参数的普通等值谓词会更新 0 行。最终 SQL 使用 `CAST(? AS JSON)` 做类型明确的原快照条件更新后 GREEN，旧 attempt、制品坐标和已发布事实仍原样保留。
- 第二次 live 全等门禁进一步证明每个失败设计 work 的历史为“旧 `expected_results` 等值规则误拒 + 新身份标签误拒”，当前实现把更早且已审计的旧规则历史误当成任意业务失败而拒绝。线上只读状态构成真实 RED；最终序列校验只允许零个或多个连续的旧规则误拒前缀，随后一个或多个连续的身份标签误拒后缀，最新诊断仍须与 work/task 一致。交错、倒序、第三类错误或非终态 attempt 的反例继续失败关闭。
- 第三次 live 只读门禁确认历史工作图还保留一个 `SUPERSEDED` 缺事实占位项。最终通用门禁只允许规划器能够确定性重建、coverage/诊断/accepted hash/租约/业务行均为空，且全部历史 attempt 都是连续终态零写入 `request_too_large` 的占位项；通用技术失败白名单不再复用。恢复 mutation 仍只重排失败设计 work，占位项及其历史 attempt 原样保留。
- 规格复审随后指出，仅检查占位项稳定身份和零写入仍可能误放父项、拆分深度或材料坐标漂移的近似记录。新增 `registration-lineage` 真实 MySQL 反例先证明旧门禁会接受错误父项/拆分层级，再逐项比较规划器登记的 identity、Skill/operation、ordinal、材料/文档/来源、evidence/context、功能/测试点、父项和拆分深度；任一不一致均失败关闭。
- 为避免身份标签验证在畸形长输入上出现明显 CPU 放大，验证器先用最大可组成长度排除不可达切片，再以单调双指针检查是否存在可能长度的可达区间。新增约 14.5 KiB 的通用畸形标签防失控测试，100 次公共验证须在宽松 2 秒保护窗内结束；该保护窗只用于发现复杂度回退，不是产品响应时间指标，也不继续扩展为性能基准。
- 保留明确拒绝对照：跨功能、跨测试点、跨事实拼接、待确认测试点支撑正式标签、纯通用名称、角色/环境/阈值/状态/错误码臆造、部分业务写入和近似恢复状态。

## 测试与静态检查

- validator 聚焦：36/36 通过。
- REQ-TGV2-015 相关测试：`StructuredGenerationAcceptanceStoreIntegrationTest` 460、`V2StructuredAllGenerationCoordinatorTest` 8、`FunctionalTestcaseV2ValidatorTest` 36，共 504/504 通过。
- 完整后端：1117/1117 通过，失败 0、错误 0、跳过 0；最终 Maven `BUILD SUCCESS`（2026-09-01 13:09+08）。
- 前端：Vitest 58/58 通过；TypeScript 类型检查、ESLint、Vite 生产构建均通过。本轮没有前端代码变更。
- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：退出码 0，仅有 Git 的 LF/CRLF 工作副本提示。
- 新增行扫描：凭据模式 0、真实样本/任务/知识库特判 0、`TODO`/`FIXME`/`System.out`/`printStackTrace`/`console.log` 0。

## 行为边界

- 名称/标题先匹配当前功能名称、功能路径末级和当前测试点；正式候选不得使用待确认测试点作为身份依据。还允许单一事实或引文中的连续短语，或 scope 与最多一个完整语义片段的组合；不允许把多条事实拼成一个身份。
- 通用包装词按可达左右边界识别任意层剥离结果；索引状态与标签长度线性，不物化左右组合。terminal slice 先按语义支配关系收敛，再用 KMP 线性匹配事实；空值或纯包装词继续失败关闭，且不依赖固定包装层数。
- 执行动作、预期、评价、异常路径和结果采集继续逐事实来源校验，NUL 或其他人工边界不能把两条事实伪装成一个来源。
- 显式恢复要求任务、work、历史诊断、accepted hash、coverage、租约、运行 attempt、投影和制品坐标全部精确闭合；事务内二次校验，并发只允许一个成功。
- 恢复只重新排队失败的 V2 用例设计 work；已完成事实投影和历史 attempt 保留，不触碰 KEE、材料解析或其他任务。

## 双轴复审

- 规格/领域轴：P0=0、P1=0、P2=0、P3=1；生产实现、规格和恢复失败关闭逻辑无缺口，唯一 P3 是固定墙钟测试在不同机器上的区分余量仍可更强。该测试已收敛为宽松防明显失控保护，不作为产品 SLA，也不继续扩大性能工程范围。
- 标准/实现轴：首轮发现 terminal slice CPU P1，次轮发现不可达边界 CPU P2，均已按上述 TDD 修复；最终复审 P0=0、P1=0、P2=0、P3=0。

## 部署与 live 门禁

- 首次不可变 JAR `testcase-agent-backend-v2-identity-label-recovery-20260901-61db48e.jar`（54,537,093 bytes，SHA-256 `FD913376856D396101610433849E51D1E86E24F3FA4E8625501174895CF6AC0F`）已按单实例、3060 秒外层等待部署为 PID 14148。8082 `/api/tasks`、`/api/task-options` 与 5173 代理均为 HTTP 200，Flyway 仍为 V24；33 张表的计数、逐表摘要和全库摘要 `2256A7189B176C141B9B687C58560E6915F6CABF7E40F76F6688730808A3D7BD` 前后完全一致。
- 第二个不可变 JAR `testcase-agent-backend-v2-identity-label-recovery-live-20260901-40a7282.jar`（54,539,052 bytes，SHA-256 `30377B298B338D7FDFBE66F6B5604560AB88054503B8520A192FBEE83C69B1A7`）部署为唯一 PID 37648 后，API、代理、V24 和上述数据库摘要仍完全相同，但 `canRetry` 仍为 false。只读历史证明原因是上述两个退役规则的连续 attempt 谱系，而非副作用；仍未发送 retry。
- 最终提交 `f6eb0a78c8296a2c00b9e72cc82784301244b243` 已推送到 `origin/codex/enforce-formal-testcase-grounding`。从该精确提交构建的不可变 JAR 为 `backend/target/testcase-agent-backend-v2-identity-label-recovery-final-20260901-f6eb0a7.jar`，54,540,691 bytes，SHA-256 `4657DFC424F9D3DC49A9CD59184317CA11E35636F1FA3A4D48EDCD3B9EF47154`。
- 最终 JAR 按单实例、隐藏窗口和 3060 秒外层等待部署为唯一 8082 PID 36072；8082 `/api/tasks`、`/api/task-options` 与 5173 `/api/tasks` 代理均为 HTTP 200，stderr 为 0 bytes。启动日志确认 Flyway V24 已校验且无需迁移。
- 部署前后任务总数均为 23、在途均为 0，目标任务仍为 `PARTIAL/FAILED/UNABLE_TO_GENERATE`，5 个 work、9 个 attempt、artifact 和全部 33 张表的计数/逐表摘要均完全相同；数据库总摘要均为 `2256A7189B176C141B9B687C58560E6915F6CABF7E40F76F6688730808A3D7BD`。唯一预期变化是只读 API 计算的 `canRetry=false -> true`，没有执行 retry、创建任务、调用 KEE 或写入业务数据。
- 可核验证据目录：`backend/target/acceptance/tgv2-015-superseded-fallback-final-deploy-20260901-f6eb0a7`，其中 `snapshot-PRE.json` 与 `snapshot-POST.json` 保存内容安全的逐表计数/摘要、状态、运行 JAR 和 PID。
