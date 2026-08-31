## 1. 冻结跨仓库接口

- [x] 1.1 与 KEE 共同冻结协议 V1 请求、规范成功、遗漏单元、不可用同级候选、多证据候选和稳定失败 JSON 样例。`[Req-ID]: REQ-AFCE-001~004, REQ-AFCE-007~008`
- [x] 1.2 冻结 `window_key` 与 `candidate_ref` 的独立字面量 SHA-256 测试向量，并证明两个仓库一致。`[Req-ID]: REQ-AFCE-001, REQ-AFCE-004`
- [x] 1.3 记录 Java 既有未提交文件和 CodeGraph 调用影响基线；发现冲突时停止覆盖。`[Req-ID]: REQ-AFCE-008`

## 2. 候选 DTO、远程映射和独立校验 TDD

- [x] 2.1 为精确字段、协议版本、窗口回显、目标/上下文闭包和旧接口兼容编写 RED 测试。`[Req-ID]: REQ-AFCE-001~002, REQ-AFCE-008`
- [x] 2.2 实现新增候选 DTO、`StructuredSkillExecutionPort` 方法和 `WebClientKnowledgeAgentAdapter` 精确映射。`[Req-ID]: REQ-AFCE-001~002, REQ-AFCE-008`
- [x] 2.3 为原文结果完整性、候选证据、状态/原因组合、只能降级和哈希重算编写 RED 测试。`[Req-ID]: REQ-AFCE-002~004`
- [x] 2.4 实现纯 `FunctionCandidateExtractionValidator`，无 HTTP、SQL、重试或页面职责。`[Req-ID]: REQ-AFCE-002~004`

## 3. 三表台账和原子正式投影 TDD

- [x] 3.1 为三张新增表、唯一约束、外键、正式功能关联和迁移回滚编写真实 MySQL RED 测试。`[Req-ID]: REQ-AFCE-005, REQ-AFCE-008`
- [x] 3.2 新增一份 Flyway 迁移，不修改历史迁移文件。`[Req-ID]: REQ-AFCE-005`
- [x] 3.3 为逐窗口原子接收、零部分写入、同键同哈希幂等、同键不同哈希拒绝、租约和并发单赢家编写 RED 测试。`[Req-ID]: REQ-AFCE-005`
- [x] 3.4 扩展 `StructuredGenerationAcceptanceStore`，只把 Java 最终接受候选投影到正式功能清单。`[Req-ID]: REQ-AFCE-003, REQ-AFCE-005`

## 4. 拆分、恢复和真实任务状态 TDD

- [x] 4.1 为最多 32 单元、确定性拆分、上下文重算、同任务重试、单目标终止和已完成同级保护编写 RED 测试。`[Req-ID]: REQ-AFCE-001, REQ-AFCE-007~008`
- [x] 4.2 只把功能提取编排切换到候选协议，并复用现有租约、心跳、重试和工作身份。`[Req-ID]: REQ-AFCE-005, REQ-AFCE-007~008`
- [x] 4.3 为 `COMPLETED`、`PARTIAL`、`FAILED` 以及处理/覆盖双轴编写完成门槛和仓储 RED 测试。`[Req-ID]: REQ-AFCE-006`
- [x] 4.4 实现结构化任务真实状态投影，不改变旧任务和 Markdown 语义。`[Req-ID]: REQ-AFCE-006, REQ-AFCE-008`

## 5. API、页面和恰好两个 Excel 工作表

- [x] 5.1 为候选/原文状态中文投影、正式计数、内部数据不泄露和 API/Excel 同源编写后端 RED 测试。`[Req-ID]: REQ-AFCE-005~006, REQ-AFCE-008`
- [x] 5.2 扩展 `StructuredGenerationTaskDetail`、仓储读取和双工作表导出；缺口只进入审查表。`[Req-ID]: REQ-AFCE-005~006, REQ-AFCE-008`
- [x] 5.3 为任务页中文状态、部分完成说明、旧详情兼容和内部键不泄露编写前端测试并实现最小 UI。`[Req-ID]: REQ-AFCE-006, REQ-AFCE-008`

## 6. 复审、部署和唯一真实任务验收

- [x] 6.1 运行 CodeGraph 影响复审、Java 定向/真实 MySQL/完整回归、前端测试/typecheck/lint/build、严格 OpenSpec、diff 和敏感信息门槛。变更级门槛结论为完整后端 `623/620/3`，`historical baseline failures=3`、`new regressions=0`，不得表述为 `623/623` 全绿；详见 `evidence/java-6.1-baseline-failure-audit.md`。`[Req-ID]: REQ-AFCE-001~008`
- [x] 6.2 在 KEE 协议 V1 合成验收通过后部署单个 Java 服务，并完成合成 API/数据库/页面/双工作表对账。安全证据见 `evidence/java-6.2-deployment-synthetic-acceptance.md`。`[Req-ID]: REQ-AFCE-001~008`
- [ ] 6.3 创建最多一个正确梅州真实任务；失败修复后只重试同一 taskId，收集最终 API、数据库、页面和 Excel 证据。`[Req-ID]: REQ-AFCE-005~008`
- [ ] 6.4 记录暂缓的 1000 页真实验收和剩余限制，停在 commit、push、merge、archive 前等待用户决定。`[Req-ID]: REQ-AFCE-001~008`
