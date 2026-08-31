# REQ-TGV2-012 严格诊断恢复与部署证据

## 变更与 TDD

- 代码提交：`1e09b051132eabfd12eb625c5f0693014969bc19`，已正常推送到 `origin/codex/enforce-formal-testcase-grounding`。
- 初始 RED：10 项真实 MySQL 聚焦测试中，旧实现对 8 个污染/不一致诊断错误返回可恢复；最小实现后 10/10 GREEN。
- 复审 RED：两个 `FACT_ATOMICITY_INVALID` 窗口分别使用合法 `[0]`、`[1]` 路径时，旧实现误拒恢复；严格三层比较收窄到 `FACT_DIRECT_EVIDENCE_UNSUPPORTED` 后 GREEN。
- 修复后受影响真实 MySQL 门禁：严格诊断与原子性用例 16/16，通过；原子性恢复、并发与回滚相关用例 13/13，通过。
- 既有代码门禁（同一差异、P1 修复前已执行）：相关聚焦 62/62、完整 `StructuredGenerationAcceptanceStoreIntegrationTest` 352/352、完整后端 983/983、前端 58/58，前端 lint 和包含 typecheck 的 production build 通过。P1 仅移动恢复分支门禁并由上述 16+13 项覆盖，未重复执行无影响的全量套件。
- OpenSpec strict、`git diff --check`、新增行敏感扫描通过；敏感扫描结果为 0。

## 双轴复审

- Spec Compliance：P0=0、P1=0、P2=0、P3=0。
- Standards/Security：P0=0、P1=0、P2=0；保留一个非阻断 P3（task/work/attempt 三个持久化投影存在可进一步封装的诊断三字段组合）。
- 可恢复错误码白名单未扩大；内部规则原因不参与自动或显式恢复资格扩张。

## 精确构建与运行物

- 精确代码提交：`1e09b051132eabfd12eb625c5f0693014969bc19`。
- JAR：`backend/target/testcase-agent-backend-direct-evidence-strict-retry-20260901-1e09b051.jar`。
- 字节数：`54508806`。
- SHA-256：`8FCA9E9080BA5E681CB402DE6D6CDC47DD3FA7EBC90085EA114DE3E64EFF766D`。
- Java PID：`7924`；知识代理外层等待配置：`3060s`。
- 前端 PID：`7540`。
- 8082 `/api/tasks`、5173 `/`、5173 `/api/tasks` 代理、KEE `/health` 均返回 HTTP 200。当前构件未暴露 Actuator health，`/actuator/health` 返回 404，因此以真实业务 API 作为后端运行门禁。
- KEE 容器只读身份：`c8f8d7fc34df3f43db58f518777aec1c025076a4d7f9f390925cbb75b91bed75`；镜像 `sha256:fa9fc56758f5f17bf98f7640f09fcb285b0b4d5566620d93f291551f5d36189c`；running/healthy/restart=0。

## Live 零副作用门禁

- 部署前快照：`backend/target/acceptance/direct-evidence-strict-retry-deploy-20260901-001004/snapshot-predeploy.json`。
- 部署后快照：`backend/target/acceptance/direct-evidence-strict-retry-deploy-20260901-001004/snapshot-postdeploy.json`。
- 前后均为任务总数 22、在途 0、Flyway V23。
- `generation_task` 与 32 个关联表的计数/行摘要综合 SHA-256 前后均为 `3897CDF3AEA96396D5934A697FA96EEF0ED12D21840BE391645D54B2366D8371`。
- 目标任务 `fc53c0ef-cf52-4847-ac72-764082387e78` 保持 `PARTIAL/FAILED/UNABLE_TO_GENERATE`、制品坐标存在、失败 work/attempt 数量不变。
- 目标任务只读 `canRetry=false`，公开原因为“V2 事实失败状态不符合安全恢复条件”。安全只读核对证明两个失败窗口各自 work/latest-attempt 路径合法但不相同，task 只保存最后一个路径，因此不满足 REQ-TGV2-012 的 task/work/latest-attempt 三层规范表示一致；该拒绝是严格门禁预期结果，不是部署副作用。
- 本轮 POST retry/create/cancel 次数均为 0；未修改 KEE 或 MySQL 生命周期。

## 本轮验收口径

- 已执行：用户态详情 API、数据库只读快照与 API 状态/计数对账、前端 unit/type/lint/build 门禁。
- Excel 程序回读入口和既有回归保持可用；本轮没有创建或重试业务任务，因此没有生成新的同任务 artifact，也未伪造新的 Excel 回读结果。
- 页面视觉、按钮交互和浏览器兼容性未执行，不表述为通过，也不作为本轮发布阻塞项。
- 6.10 仍未执行。
