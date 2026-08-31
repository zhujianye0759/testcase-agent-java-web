# REQ-TGV2-013 Java 代码门禁证据（2026-09-01）

## 结论

`REQ-TGV2-013` 已完成代码、真实 MySQL、并发、事务、迁移、前端静态门禁和双轴复审。该能力只依据 V2 版本化协议、有限技术失败类型、完整 attempt 历史和全业务零写入闭集，不包含任务、项目、知识库、文档、材料类型或数量特判。

本文件记录代码门禁、正式部署、Flyway V24 和 live 零副作用资格门禁。业务 retry 未发送，第二套业务夹具未创建。

## RED 与根因

- Java 本地 V2 请求预算超限原来与 KEE 容量错误共用 `request_too_large`；已新增 RED 证明本地拒绝不得发出 HTTP，并改为不可恢复的 `invalid_request`。
- 无索引 artifact 三坐标扫描会让无关任务的恢复产生交叉锁；V24 改为 artifact ID 和路径摘要的数据库唯一索引，生产查询按 ID、路径固定顺序分别定位。
- attempt 前缀 `FOR UPDATE` 在复合索引上产生跨 UUID next-key 锁；真实 MySQL 压测在旧实现上复现死锁。修复后由父 work 排他锁和外键阻止新 attempt，避免 attempt 前缀范围锁。
- 普通 attempt 查询在外层 `REPEATABLE READ` 已建立 read view 时会隐藏新提交 attempt。精确 RED 为 `expected 0, actual 1`。显式 retry 现使用专用 `READ COMMITTED`；技术恢复若加入不兼容的旧快照事务则在 mutation 前失败关闭。

## GREEN 证据

- 关键隔离、回滚与并发测试：8/8，通过。
- REQ-TGV2-013 聚焦与迁移测试：28/28，通过。
- 受快照列影响的聚焦回归：47/47，通过。
- 相关七个测试类：478/478，通过，0 失败、0 错误、0 跳过。
- 后端全量：1037/1037，通过，0 失败、0 错误、0 跳过。
- 前端 Vitest：58/58，通过；ESLint 0 warning；`vue-tsc --noEmit` 与 Vite build 通过。
- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：通过（仅既有 LF/CRLF 提示）。
- 新增行敏感信息扫描：0 命中；临时调试标记扫描：0 命中。

## 并发和失败关闭边界

- 同任务并发 retry 只有一个成功。
- 不同任务、不同 artifact 的并发恢复均成功且不死锁；跨任务死锁修复后压力验证 20/20 通过。
- 恢复等待父 work 锁期间提交的业务行或新 attempt 均可见，并使恢复失败关闭。
- attempt 必须从 1 连续到 N、全部 `FAILED`、完成时间非空、失败类型全部属于有限技术白名单且没有验证诊断；序号断档、历史成功和历史业务错误全部拒绝。
- 事务中途异常整体回滚；mutation 不创建 attempt，下一次 claim 才创建新 attempt。
- V24 只约束 artifact ID 与路径身份；相同工作簿内容 SHA 允许重复。

## 双轴复审

- 冻结合同、适应性与兼容性轴：P0=0、P1=0、P2=0、P3=0。
- 实现、MySQL 锁、事务与并发轴：P0=0、P1=0、P2=0、P3=0。

复审确认：历史无来源 `request_too_large` 只在完整零写入闭集下兼容；未来本地预算拒绝为 `invalid_request`；V1、旧 operation、业务校验、结构化输出错误和任何部分写入状态继续失败关闭。

## 构建与部署门禁

- 生产代码提交：`6a3bf2fdfb5766bead1f53d7c049cd082a8af897`。
- 不可变 JAR：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\testcase-agent-backend-v2-zero-write-technical-retry-20260901-6a3bf2f.jar`。
- JAR 字节数：`54,515,198`；SHA-256：`E1ECBA6995AE5CBA4BC810C990B7D022EF7680A8356CB410D4BEE507F74A1C18`。
- 唯一 8082 Java 进程 PID：`23248`；实际命令行加载上述不可变 JAR；外层 KEE 等待时间保持 `3060s`。
- 8082 API 与 5173 前端代理均返回 HTTP 200；5173 既有进程未重启。
- Flyway 从 V23 正常向前应用至 V24。部署前 artifact ID 重复组为 0，artifact 路径重复组为 0；未手工修改数据库。
- 启动后标准输出中的 `ERROR`/`Exception` 计数为 0，标准错误非空行计数为 0。

## Live 零副作用资格门禁

- 部署前后任务总数均为 23，在途任务均为 0。
- 夹具 A 的 `canRetry` 由 `false` 变为 `true`；task/work/attempt/artifact 的业务状态和既有结果未改变。
- 除 `generation_task` 外的 32 张对账表，部署前后计数和摘要完全一致，无差异表。
- V24 为 `generation_task` 增加生成列，因此包含全部列的物理摘要按预期变化；按原有 21 个非生成业务列重新计算的摘要与部署前完全一致，证明业务列未被改写。
- 正式业务 retry POST 次数为 0；新任务创建次数为 0；夹具 B 未创建；未调用或修改 KEE。

可核验证据目录：

`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\acceptance\tgv2-013-zero-write-technical-retry-deploy-20260901`

其中 `snapshot-predeploy.json`、`snapshot-postdeploy.json` 和 `comparison-safe.json` 分别记录部署前快照、部署后快照与安全差异结论；日志文件不包含材料正文、模型输出或凭据。
