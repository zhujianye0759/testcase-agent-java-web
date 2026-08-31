# Java V2 代码门禁证据（2026-08-30）

## 范围与运行边界

- 工作树：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding`
- 分支：`codex/enforce-formal-testcase-grounding`
- 交接基线：`276d4abc9e04adee7f07670b87da001796c68f18`
- 跨服务字段基线：KEE `kee-java-v2-contract-freeze-20260830.md`
- 本轮最终代码收口未部署或启动 Java，未调用真实 KEE，未创建、重试、取消或修改任何真实业务任务，未修改 KEE/MySQL/容器运行态。代码提交与推送仅在本证据和全部静态门禁完成后执行。

## 实现闭合

- 新任务冻结 workflow/input/artifact `2.0` 和版本化已审核功能范围；缺少 V2 范围时在 KEE 调用前失败关闭。
- V2 协调器仅调用 `requirement-fact-extraction` 与 `functional-testcase-design`；静态扫描旧两个 Skill/四个 operation 匹配数为 0。实际调用点仅为 `V2StructuredAllGenerationCoordinator.java` 第 184、242 行。
- 事实提取按单材料、连续目标和相邻只读 context 调用；原始 parsed-unit content 不截断，完整请求按 2–64 MiB 配置和绝对 64 MiB 门限失败关闭。
- Java 权威验证回显、引用闭合、连续引文、事实原子性、稳定事实键、跨窗口合并、完整用例字段证据、三态结果矩阵和动态正式覆盖。
- 每个事实窗口和测试点在单事务发布；同键同哈希幂等、异哈希拒绝、并发单赢家，失败零部分正式发布，重启跳过已完成工作。
- V2 详情独立分页反馈、测试点和测试用例；测试点与用例页各固定一条大业务记录，非终态隐藏可变结果和待确认计数，V1 JSON 形状不变。
- V2 Excel 每类数据只执行一条有序主查询，使用 Connector/J `setFetchSize(Integer.MIN_VALUE)` 驱动级流式读取，步骤和需求摘要在同一行聚合，缺失信息稳定排序去重；SXSSF 窗口为 1，最终仅用原子移动发布。
- 页面/API/Excel 均不投影原始 JSON、Markdown、内部枚举/键或凭据；第二张 Sheet 只读取正式用例。
- 生产代码中未发现梅州、真实任务、知识库或项目身份特判；用例数量不使用固定 2N。

## 数据库迁移

- `V20__persist_generation_v2_workflow.sql`：新增 V2 版本列、已审核功能范围、事实/引文、可测性反馈、生成结果和发布账本。
- `V21__allow_staged_material_inventory.sql`：支持材料清单分页暂存和完成闭合。
- V14–V19 未删除、未改写；历史版本缺失时走 V1 只读投影。
- `ApplicationDatabaseMigrationTest` 使用真实 MySQL/Testcontainers 验证新装、升级和约束。

## 测试与构建

- 最终聚焦门禁：`RequirementFactV2ValidatorTest` 52/52、`GenerationTaskDetailTest` 15/15、首页与创建 API 聚焦测试 16/16，均为 0 failure、0 error、0 skipped。
- 恢复路径真实 MySQL/Testcontainers：`StructuredGenerationAcceptanceStoreIntegrationTest` 340/340，0 failure、0 error、0 skipped；覆盖严格 replay JSON、固定批次证据闭合、事务回滚与并发单赢家。
- 当前源码完整后端：962/962 通过，0 failure、0 error、0 skipped，总耗时 7 分 20 秒。
  - 首次普通 `mvn test` 发现已删除源码的 `AllCompletenessGoldenFixtureTest` 旧 class 仍残留在 `target/test-classes`，额外执行出 2 个既知旧错误；这不是当前源码测试。
  - 使用 Surefire 精确排除该已退役残留类后执行全部当前源码测试得到 962/962；随后将 8 个旧 class 与 2 个旧报告移入 `backend/target/stale-test-quarantine-20260831`，未删除源码、JAR或验收证据。
- 前端：6 个测试文件、58/58 通过；`vue-tsc` 通过；ESLint `--max-warnings=0` 通过；Vite 生产构建通过。
- 前端构建：JS 138.80 kB（gzip 49.29 kB），CSS 46.06 kB（gzip 8.67 kB）。
- Excel 回读覆盖固定双 Sheet、第二张只含正式用例、公式注入、来源身份、行数漂移、原子发布和驱动流式 fetch。
- 最终不可变后端构件：`backend\target\testcase-agent-backend-generation-v2-final-20260831-B407790D2A91.jar`
  - 字节数：54,500,948
  - SHA-256：`B407790D2A919976F07DD77AD6BBA067853428D7F00D798F0FC100FCFB8F0607`

## 静态与复审门禁

- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：通过；只有工作树既有 LF/CRLF 转换提示，无空白错误。
- 新增生产行凭据字面量扫描：0；生产样本身份硬编码扫描：0。初筛命中的 `apiKey` 变量名和脱敏测试假值均不属于凭据泄露。
- Standards 复审：P0=0、P1=0、P2=0、P3=0。
- Spec Compliance 复审：P0=0、P1=0、P2=0、P3=0。
- 最终复审重点逐项闭合：V2 首页精确版本化输入、V1 七字段 JSON、事实原子性正反边界、业务字面值大小写、严格 replay JSON、失败叶与 split lineage 固定批次、冻结材料证据闭合；未发现任务、项目、知识库、文档名、页数或固定业务数量特判。

## 尚未执行的 live 验收

- 未部署或启动本轮最终 Java JAR，未应用迁移到 live 验收库。
- 未通过已部署 Java 实际调用 KEE V2。
- 未创建或运行新的真实业务任务。
- 未执行 live API/MySQL/双尺寸 Browser/最终双 Sheet Excel 同任务验收。
- 以上项目等待 KEE 主会话复核并另行安排部署和联合验收；本轮不得以本地合成结果替代 live 证据。
