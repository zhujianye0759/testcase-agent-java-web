# REQ-TGV2-014 Java 代码与部署门禁证据（2026-09-01）

## 结论

`REQ-TGV2-014` 已完成合同修复、真实 MySQL 接收与恢复事务验证、完整后端和前端静态门禁、双轴复审、提交推送、不可变构建、唯一 8082 部署及 live 零副作用核对。正式业务 retry、任务创建和 KEE 业务调用均未执行。

## 合同与业务校验

- `steps[].expected` 继续表示逐步预期，`expected_results` 改为独立的用例级总体预期，不再要求数量或文字逐项相等。
- 总体预期仍逐项执行非空、数量上限、读者安全和业务依据校验；正式与待确认用例均不能借此绕过事实、证据、三态、重复身份或敏感信息门禁。
- 数据库存储和 Excel 投影分别保留逐步预期与总体预期，测试覆盖一个总体预期对应多个连续步骤。
- 旧错误规则产生的 `TESTCASE_EXPECTED_ORDER_INVALID` 仅作为历史恢复识别条件保留，不再由新 validator 产生。

## 显式窄恢复

- 仅接受 V2、任务为 `FAILED/FAILED/PENDING`、顶层固定诊断位于 `$.artifact_export`、`result_snapshot` 与 artifact 三坐标为空的历史形态。
- 失败设计 work 必须为零 accepted hash、无租约、无运行 attempt，完整 attempt 历史必须是固定 `TESTCASE_EXPECTED_ORDER_INVALID` 业务校验失败。
- 被正式事实替代的缺事实 fallback 必须非空、与受影响功能一一对应、零业务写入，并从已审核功能和已发布事实重新计算稳定身份。
- mutation 只重新排队旧误拒的设计 work 并终止对应 fallback；已完成事实、引文、反馈和发布账本保持不变，下一次执行不重新调用事实提取。
- 任一 result snapshot、部分用例投影、artifact、身份漂移、fallback 缺失或重复、近似状态、并发变化或事务异常均失败关闭。

## RED 与 GREEN

- 受监视 RED：6 个断点真实失败，覆盖不受支持的总体预期被旧实现接收，以及 result snapshot、fallback 缺失和 fallback 身份漂移被旧恢复门禁误放。
- `StructuredGenerationAcceptanceStoreIntegrationTest` 全类真实 MySQL：428/428，通过，0 失败、0 错误、0 跳过。
- 相关 API、协调器、详情、Excel 与 V1 兼容回归：90/90，通过。
- 最终后端全量：1064/1064，通过，0 失败、0 错误、0 跳过。
- 最终内部类型命名收口后，恢复事务与协调器聚焦：22/22，通过。
- 前端 Vitest：58/58，通过；ESLint 0 warning；TypeScript 检查与 Vite 生产构建通过。
- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：通过，仅有工作树 LF/CRLF 提示。
- 新增行敏感信息扫描：0 命中。

## 双轴复审

- 冻结合同、兼容性与适应性轴：P0=0、P1=0、P2=0、P3=0。
- 实现、锁、事务、并发和失败关闭轴：P0=0、P1=0、P2=0、P3=0。
- 复审确认不含 taskId、项目、知识库、文档、材料、数量或样本特判。

## 提交、构建与部署

- 生产代码提交并推送：`790d73c`（分支 `codex/enforce-formal-testcase-grounding`）。
- 不可变 JAR：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\testcase-agent-backend-v2-expected-results-recovery-20260901-790d73c.jar`。
- JAR 字节数：`54,520,630`；SHA-256：`F7A64C3697CA778213D43DBFDA35E2542F7AD383EF35ED2E744F13F19B0523D8`。
- 唯一 8082 Java 进程 PID：`18036`；实际命令行加载上述不可变 JAR；外层 KEE 等待时间保持 `3060s`。
- 8082 `/api/tasks` 与 5173 `/api/tasks` 代理均为 HTTP 200；5173、MySQL 和 KEE 未重启。
- Flyway 保持 V24，本次没有数据库迁移或手工数据库写入。
- 新进程标准输出中的 `ERROR`/`Exception` 计数为 0，标准错误非空行计数为 0。

## Live 零副作用门禁

- 部署前后任务总数均为 23，在途任务均为 0。
- 夹具 A 当前既有状态、诊断、work/attempt 分布和 `canRetry=true` 完全不变；该 live 现场当前属于既有直接依据恢复形态，不把它伪报为总体预期恢复的运行证明。
- 33 张任务对账表的计数与 SHA-256 摘要全部相等；全库任务摘要相等。
- 总体预期窄恢复由真实 MySQL、并发、回滚和协调器测试证明；由于 live 中不存在对应历史误拒形态，本轮未构造或手工修改数据库来伪造运行现场。
- 正式 retry POST、任务创建、KEE 业务调用、数据库 `UPDATE/INSERT/DELETE` 次数均为 0。

可核验证据目录：

`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\acceptance\tgv2-014-expected-results-recovery-deploy-20260901`

其中 `snapshot-predeploy.json`、`snapshot-postdeploy.json`、启动日志与脚本记录前后快照、运行物身份和安全部署过程；文件不包含材料正文、模型输出或凭据。
