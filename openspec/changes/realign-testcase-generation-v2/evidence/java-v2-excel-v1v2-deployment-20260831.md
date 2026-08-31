# Java V2 Excel 版本分流部署门禁（2026-08-31）

## 部署范围

- 部署源码固定为提交 `0abb7fb21fe21cf090cdb3bd8fbb15e8f43c43f8`；构建前分支为 `codex/enforce-formal-testcase-grounding`，工作树为空。
- 本轮只替换 Java 后端 8082；已运行的前端 5173 保持原进程，MySQL、Docker 和 KEE 均未重启或修改。
- 未创建、重试、取消任何任务，未调用 KEE structured operation，未直接修改数据库业务状态。
- 运行明细位于 Git 忽略目录：`backend/target/acceptance/java-v2-excel-v1v2-deployment-20260831-202552`。

## 代码与测试门禁

- V2 Excel 第一张固定为“需求可测性反馈”，第二张固定为“测试用例”；V1 旧入口继续使用“需求与功能清单审查发现”“测试用例”。
- 当前源码完整后端回归：962/962；真实 MySQL/Testcontainers 恢复与事务/并发回归：340/340。
- Excel/V1/V2 详情相关最终增量回归：62/62；前端相关回归：42/42。
- 前端完整回归：58/58；类型检查、ESLint 和生产构建均通过。
- OpenSpec strict、`git diff --check`、敏感新增行扫描均通过；敏感新增行命中 0。
- Standards 与 Spec Compliance 最终复审均为 P0/P1/P2/P3 = 0。

## 不可变构件与运行态

- 构建命令：`backend/mvnw.cmd -q -DskipTests package`，使用现有本地依赖缓存。
- 不可变 JAR：`backend/target/testcase-agent-backend-v1v2-excel-0abb7fb-CD30F2B51854.jar`。
- 字节数：54,501,512。
- SHA-256：`CD30F2B518549A91B99A7EED0A83935153018C9734C90E9BA7A69E01E3A3DAC4`。
- 后端 PID 48684，8082 只有一个监听；`GET /api/tasks?page=0&size=1` 返回 200。
- 模型调用外层等待保持 3060 秒。
- 前端 PID 35684，5173 只有一个监听；首页返回 200，标题为“测试用例智能生成”；`/api/tasks` 代理返回 200。
- 后端 stderr 为 0 字节；stdout/stderr 中 `ERROR`、`Exception` 和凭据特征命中 0。

## Flyway 与数据等值

- 部署前后 Flyway 均为 V23 `persist validated v2 result replay`，启动日志明确显示 schema 已是最新状态且不需要迁移。
- 对账 43 张业务表，发生行数变化的表为 0。
- 任务总数 21 → 21，在途任务 0 → 0。
- 历史 V1 已完成制品通过详情 API 与下载 API 返回 200；下载内容类型为 XLSX，内存回读仍恰好包含“需求与功能清单审查发现”“测试用例”，未重生成、未改名。

## 回滚边界与后续工作

- 旧不可变 JAR `testcase-agent-backend-generation-v2-final-20260831-B407790D2A91.jar` 仍保留；如需回滚，只停止当前精确 PID 并用同一 3060 秒安全配置启动该旧 JAR。
- Flyway V23 保留，不执行逆迁移，不删除或改写历史数据。
- 本轮只完成 6.9；6.10 的两套材料联合验收未执行，也没有用本部署门禁替代该业务验收。
