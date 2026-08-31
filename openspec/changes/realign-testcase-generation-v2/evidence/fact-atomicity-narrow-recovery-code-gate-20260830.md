# V2 事实原子性与显式窄恢复代码门禁（2026-08-30）

## 结论与边界

- 根因位于 Java `RequirementFactV2Validator`：旧逻辑把连接字符和标点机械等同于多个独立义务，导致一个可测试原子行为被误拒。KEE 冻结合同与 Skill 未修改。
- 修复只使用中英文通用语法结构，不包含任务、项目、知识库、文档、章节、页数、功能数量或领域动作词表。
- 旧模型 statement 未持久化且当前不可证明；本证据不声称知道其正文，只记录安全错误码、路径和可复现合成语法。
- 本轮没有调用 KEE、没有 retry 或创建 fixture 任务、没有手工修改业务数据库。仅按批准流程向前应用 Flyway V22 并替换唯一 Java 8082 运行物；MySQL、KEE 和前端均未重启。

## RED → GREEN

- 原子性：字段集合、成对范围和条件前缀不再因连接字符误拒；明确重复义务标记或独立第二主体仍拒绝。新增布尔字段和独立第二主体混合反例先失败，最小语法修复后 `RequirementFactV2ValidatorTest` 10/10 通过。
- 中文条件：`创建时间`/`更新时间` 字段及逗号条件正例实际通过；条件边界选择最终 `一致时，`，没有为该已正确行为修改生产代码。
- 首次恢复资格：V15 reconciliation run 附着于 no-fact fallback 时，21 个近似状态中仅该新增反例先失败；补齐 work-owned 闭集与锁内复核后 21/21 通过。
- `SUPERSEDED` 重激活：旧业务 fact、V15 run、跨函数 V2 fact（以 `first_work_item_id` 归属 fallback）先分别证明可误激活；补齐闭集后 3/3 均拒绝并保持 `SUPERSEDED`。
- 部署前门禁进一步证明：生产已接收的 `unable_to_generate` 结果可以给出比 planner 固定提示更具体、但仍非空且通过生产 validator 的原因；恢复资格不能机械要求两份原因文字完全相等。RED 1/1 失败后，资格判断和 `SUPERSEDED` 重激活统一改为从持久化结果重建、调用生产 `FunctionalTestcaseV2Validator`，再核对输入、结果和 accepted hash。
- 空原因、携带测试用例、哈希漂移、额外业务行等近似状态仍失败关闭；合法更具体原因不会生成正式或待确认用例。
- 完整真实 MySQL Store：287/287 通过，0 failure、0 error、0 skipped。
- 聚焦资格门禁：23/23 通过；相关协调器、HTTP retry、V2 planner、业务 validator 与迁移：58/58 通过，均为 0 failure、0 error、0 skipped。

## 完整回归与静态门禁

- 完整后端：868 总数，865 通过，1 failure、2 errors、0 skipped。
  - 3 项与交接基线完全相同：`AllCompletenessGoldenFixtureTest` 2 项、`MarkdownBatchPersistenceIntegrationTest` 1 项。
  - 本 change 新增回归：0；禁止表述为 868/868 全绿。
- 前端：6 个测试文件、57/57 通过；`vue-tsc`、ESLint `--max-warnings=0`、Vite 生产构建通过。当前修复没有修改前端。
- Excel 回读门禁已覆盖固定双 Sheet、正式用例单独投影、公式注入、来源顺序、行数闭合和原子发布；当前修复没有修改导出代码。
- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：退出码 0；仅既有 LF/CRLF 转换提示，无空白错误。
- 生产样本身份扫描：0；新增凭据字面量：0。

## 双轴复审

- Standards：P0=0、P1=0、P2=0、P3=0。
- Spec Compliance：P0=0、P1=0、P2=0、P3=0。
- 复审确认：首次资格和后续重激活都执行闭世界业务投影检查；并发恢复仍由任务行锁和同一事务保证单赢家/整体回滚；没有样本特判。

## 构建、部署与 live 零副作用门禁

- 最终不可变 JAR：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\testcase-agent-backend-fact-atomicity-recovery-final-20260830-9EECB7C9D098.jar`。
- 大小：54,402,559 bytes；SHA-256：`9EECB7C9D098C63C8A11CC1C638B968E4B74AE724B88F213B8781A8318ED4739`。
- 唯一 8082 Java PID：`20008`；实际命令行只加载上述 JAR；知识代理外层等待仍为 3060 秒。
- 8082 `/api/tasks`、fixture A 详情、5173 页面及 5173 API 代理均返回 HTTP 200；8082 仅有 PID 20008 一个监听者。
- Flyway 当前版本 V22；启动日志显示 20 个 migration 校验成功、schema 已是 V22、无需重复迁移；stderr 为 0 bytes。
- 部署前后任务总数 21、在途 0；fixture A 仍为 `PARTIAL/FAILED/UNABLE_TO_GENERATE`，原错误码和路径、制品存在性、4 个 work 与 4 个 attempt 状态均不变。
- `snapshot-pre-final-deploy.json` 与 `snapshot-final-live-gate.json` 的全部 32 张表计数和逐表 SHA-256 完全相等，数据库总摘要均为 `CDECD182D2F692AE5ED91EA202CA0AD54B961D2CC428F342C2ECC6EBCACB1BE8`。
- 唯一预期变化：公开资格 `canRetry=false` 变为 `canRetry=true`；没有业务表写入、没有新 attempt、没有 KEE 调用。
- 运行证据目录：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding\backend\target\acceptance\fact-atomicity-recovery-deploy-20260830`。

## 当前等待点

- fixture A 已满足同任务窄恢复代码与 live 门禁，但尚未发送正式 retry。
- fixture B 尚未创建。必须等待 KEE 主会话明确放行后，才能对 fixture A 发送一次正式 retry；本证据不构成自动执行授权。
