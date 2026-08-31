# Java 6.2 部署与合成验收证据

## 结论

- companion 6.2 完成。部署后的 Java 生产构件、Flyway V19、API、MySQL、页面和 Excel 已使用同一条独立合成任务完成对账。
- 未创建或重试梅州真实业务任务，未重新上传、解析、OCR、PageIndex 或图谱处理任何文档，未重启或清理 MySQL、KEE 依赖容器、镜像、缓存或卷。
- KEE 实时调用通过 Java 生产适配器、映射器和校验器，但该次模型结果没有 Java 最终接受项；门禁在数据库写入前失败关闭，数据库零写入。随后只使用双方批准的共享 `canonical-success` 固定夹具，在相同输入窗口内通过生产仓储、事务接收、详情投影和导出器完成确定性持久化对账。本文不把实时模型结果表述为已提交业务结果，也没有拼接历史任务证据。

## 部署身份

- 工作树：`D:\workspace\testcase-agent-java-web\.worktrees\enforce-formal-testcase-grounding`
- 分支：`codex/enforce-formal-testcase-grounding`
- 交接 HEAD：`276d4abc9e04adee7f07670b87da001796c68f18`
- 后端构建：`.\mvnw.cmd -q -DskipTests package`，通过。
- 不可变 JAR：`backend\target\testcase-agent-backend-afce-kee72-20260826.jar`
- JAR 大小：`54,184,110` 字节；SHA-256：`66B8F7E9F6690F8B7D05FD51BFA82E38702BCF0FE6C3FC11252D9D2E1F56845F`
- 后端运行：PID `54652`，唯一监听 `8082`；命令行精确指向上述不可变 JAR；`GET /api/tasks` 返回 200。
- 页面验收发现顶部把待确认测试用例简称为“待确认候选”，与下方待确认功能候选数量产生歧义。新增 RED 精确得到 22 项中 1 项失败，最小修复只把顶部标签改为“待确认用例”；聚焦测试 22/22、完整前端测试 53/53、typecheck、lint、build 均通过。
- 前端构建：`npm run build`，包含 typecheck 和 Vite build，通过；最终 `dist/index.html` SHA-256 为 `9CEFEA839088BDE675FD68E55812C16BC59E9427AF5A9C00616C5AB70DA06893`。
- 前端运行：PID `32096`，唯一监听 `5173`；页面和 `/api/tasks` 代理均返回 200。
- Java 启动日志：错误行 0、凭据形态行 0、成功启动行 1。
- KEE 运行镜像与 `latest` 均为 `sha256:1b276357501b4d6ce9df165294850a1e7617c41b6efafe3eaa9cfddc0ab75cae`，healthy，restart count 为 0。

## Flyway 与数据库

- 启动前：V18 `persist function list target quotes`。
- 启动后：V19 `persist auditable function candidates`，`success=1`；日志记录由 V18 正常迁移到 V19，未手工改库。
- V19 新表：`structured_function_source_outcome`、`structured_function_candidate`、`structured_function_outcome_candidate`。
- 合成任务：`task-meizhou-acceptance-001`；任务总数由 18 增至 19，完成后在途数为 0。
- 任务状态：顶层 `PARTIAL`；结构化处理 `COMPLETED`；正式覆盖 `PARTIAL`；制品已生成。
- 任务级持久化计数：来源结论 4、候选 2、正式接受候选 1、待确认候选 1、正式功能 1、来源候选关联 3、attempt 1。
- 只有正式接受候选拥有正式功能投影；待确认候选没有进入 `structured_function_list_item`。
- 合成前 18 个历史任务相关表的计数与 SHA-256 摘要在合成后保持一致，证明合成任务没有覆盖或拼接历史数据。

## KEE 合成调用与范围

- 共享夹具目录：`backend/src/test/resources/contracts/function-candidate-protocol-v1`。
- 固定窗口：4 个目标单元、1 个只读上下文单元；`window_key=365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d`。
- 外层范围恰好 1 个知识库和 1 份功能清单文档：正确梅州知识库 `7c0f5fdd-980d-4389-8105-ec97f675dac1`、文档 `37d6e052-b877-4a5c-b360-2805466c6a14`。
- 一次实时 KEE POST 通过生产 `WebClientKnowledgeAgentAdapter`、mapper、`FunctionCandidateExtractionValidator`、窗口/候选身份和证据闭包校验；由于最终接受数为 0，在任务创建和业务写入前失败关闭，没有发送第二次实时 KEE 请求。
- 确定性持久化使用同一共享 `canonical-success` 夹具，经部署 JAR 解包后的生产类运行 `GenerationTaskRepository`、`StructuredGenerationAcceptanceStore`、详情读取源和 `ApachePoiWorkbookExporter`。
- 一次性哨兵、前置摘要及安全结果位于：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826`。

## API 与页面

- 同一任务详情 API：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/api-task-detail.json`。
- API 显示正式接受 1、待确认 1、未纳入 0、原文无功能 1、未完成窗口 0；阶段计数为材料 1/1、功能清单核对 1/1、需求审查 0/0、用例设计 0/0。
- 页面明确区分“处理已完成”和“正式覆盖部分完整”，没有把 `PARTIAL` 写成完整交付；0 用例显示为“处理已完成，当前没有已验证并保存的测试用例”。
- 页面顶部明确显示“待确认用例 0 条”，下方功能候选审查显示正式接受 1、待确认 1、未纳入 0、原文无功能 1；两类数量不再使用同一名称。页面无原始 JSON、Markdown、内部枚举、候选键、窗口键、单元键、证据键或凭据。
- 1440×820：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/ui-task-detail-1440x820.png`。
- 1100×820：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/ui-task-detail-1100x820.png`。
- 完整页面：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/ui-task-detail-1440-full.png`。
- 三次页面读取的浏览器控制台 warning/error 均为 0。
- 部署前旧结构化任务 `e9e936d1-5010-4d84-8e91-5b622c618de4` 的 API 与页面仍可读取，正确显示已完成但无法形成正式用例的旧结果；无原始 JSON、内部枚举或控制台错误。截图：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/ui-legacy-structured-task-1440x820.png`。

## Excel

- 下载入口：`GET /api/artifacts/3a55aa92-89ed-4426-be7f-3ee13372381f/download`。
- 文件：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/afce-6.2-synthetic-two-sheet.xlsx`。
- 文件 SHA-256：`080A9E3A69E9A4DFACDD256390209F1A1D8E8634AD55949C090D69FC0B2FE6D9`，与任务制品元数据一致。
- 使用项目提供的 `@oai/artifact-tool` 回读并渲染，恰好两张工作表：`需求与功能清单审查发现`、`测试用例`。
- 审查表范围 `A1:M2`，包含 1 条待确认候选审查行；测试用例表范围 `A1:Y1`，只有字段标题，与该任务没有正式测试用例的 API/数据库事实一致。
- 公式扫描 0，内部键/64 位机器哈希/原始 JSON/Markdown 扫描 0。
- 回读证据：`backend/target/acceptance/afce-6.2-deployment-synthetic-20260826/excel-sheet-inspection.ndjson`。
- 渲染证据：`excel-review-sheet.png`、`excel-testcase-sheet.png`。渲染器在空白单元格内显示的“19”来自列宽绘制，不是工作簿单元格值；结构化回读和公式扫描均未发现该内容。

## 保留边界

- 最终门禁：OpenSpec strict 通过；`git diff --check` 通过（仅既存 LF/CRLF 提示）；新增行凭据形态扫描 0。
- companion 任务状态为 20/22；只勾选 6.2，6.3、6.4 保持未完成。
- 工作树继续保留交接时的受保护未提交修改；未执行 reset、clean、commit、push、merge 或 archive。
- 本次只证明已部署 Java 与 KEE V1 的协议兼容，以及共享批准夹具经 Java 生产事务、API、页面和 Excel 的同源一致性。
- 未运行新的梅州真实业务任务；真实模型输出本轮没有最终接受项，因此未将其伪装为已持久化成功。
- companion 6.3、6.4 仍未完成；继续等待 KEE 主任务决定是否进入唯一新的梅州真实任务。
