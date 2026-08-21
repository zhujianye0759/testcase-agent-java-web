# V13 Java Web 本地部署与页面证据（2026-08-22）

## 交付基线

- 实现提交：`4f84f27f6198225ec807397e3913152a1103a743`
- 分支：`codex/enforce-formal-testcase-grounding`
- 数据库：受控 Java 验收库；部署前 8 个历史任务全部终态、在途任务 0。
- Flyway：从 V12 正常增量迁移到 V13 `extend structured delivery contract`，未修改历史迁移或 Flyway history。
- 后端 JAR：`backend/target/deployments/testcase-agent-backend-4f84f27f6198225ec807397e3913152a1103a743.jar`
- JAR SHA-256：`2F0E1D34EF08132220A589A3D2CFDF432E4D9CFF8B62AAD27E5FA3CDD6583A25`
- 后端：PID `54492`，端口 `8082`；`GET /api/tasks?page=0&size=1` 返回 HTTP 200。
- 前端：Vite listener PID `20116`，端口 `5173`，命令行工作目录来自本 change 独立工作树，API proxy 指向 Java `8082`。

## 自动化门禁

- DTO/Wire/validator/coordinator/export 聚焦：82/82 通过。
- MySQL/Testcontainers/Flyway 聚焦：54/54 通过。
- 普通 Knowledge Agent 回归：35/35 通过。
- 前端：Vitest 44/44、typecheck、lint、build 全部通过。
- 全后端：400 项中 397 通过；仅保留已在未修改基线证明的 3 个既有 fixture 失败（`AllCompletenessGoldenFixtureTest` 2 项、`MarkdownBatchPersistenceIntegrationTest` 1 项），未修改旧 fixture 放行。
- `openspec validate enforce-formal-testcase-grounding --strict` 通过。
- `git diff --check` 通过；新增行敏感值形态扫描为 0。

## 真实页面只读验收

以既有结构化任务 `e9e936d1-5010-4d84-8e91-5b622c618de4` 只读验证，不创建、不重试任务：

- 1440×820：`v13-task-detail-1440x820.png`
- 1100×820：`v13-task-detail-1100x820.png`
- 两个视口均显示处理已完成、正式覆盖无法生成、审查发现、功能核对和 0 用例含义；未错误显示“完整交付”。
- 浏览器控制台错误数为 0；页面与 Java API 均从同一受控数据库读取。

## 边界

- 未修改、构建或部署 KEE。
- 未创建或重试任何业务任务。
- 未推送、合并或归档 change。
- 部署完成后等待 KEE 主任务下令执行最终唯一业务任务验收。
