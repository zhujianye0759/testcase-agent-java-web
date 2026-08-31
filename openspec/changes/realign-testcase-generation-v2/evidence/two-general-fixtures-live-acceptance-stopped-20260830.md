# V2 两套通用材料联合夹具验收——首项失败关闭证据

## 结论

本次联合验收按“首项失败后立即停止、不得 retry、不得创建替代任务”的门禁执行。夹具 A 已通过正式 Java + KEE live 链路进入 `PARTIAL`，但未产出正式用例，不满足“证据充分型至少生成一条正式用例”的验收条件。因此夹具 B 未创建，页面与 Excel 的成功同源验收未继续。

## 运行前门禁

- Java 8082、前端 5173 和 KEE 8080 均为 HTTP 200。
- KEE running/latest 镜像均为 `sha256:17ee86a5b3a6a974d5a3b72f1c375d610718cb2f2d75c391434f4ec1b358a910`，健康且 restart=0。
- Java 运行物为已批准 V2 JAR；Flyway V21 已生效。
- 创建前 Java 任务总数 20、在途 0。
- 生产代码没有 `test_fixture` 材料类别；V2 创建入口支持版本化 `ApprovedFunctionScope`。两套材料分别创建在独立、名称带 `TGV2_FIXTURE` 的测试知识库中。
- 测试知识库显式关闭 PageIndex、Wiki 和知识图谱。官方手工 Markdown 没有不可变文件哈希，因而不满足 Java 冻结范围门禁；随后通过同一 KEE 官方文件上传入口提交相同合成材料字节。原手工条目保留，不删除、不覆盖，Java 只暴露具备文件哈希的上传文档叶子。

## 夹具准备

- 隔离测试知识库：2。
- 独立当前版本：2。
- `requirements_spec` 上传文档：2；均 `completed/enabled/current`，每份 1 个 parsed unit。
- Java `task-options` 中精确测试文档叶子：2。
- 真实项目材料选择数：0；梅州、汕头、战略运管和其他业务项目均未进入范围。
- KEE 知识库创建 POST：2；手工材料创建 POST：2；带不可变哈希的文件材料上传 POST：2。
- Java 任务创建 POST 在夹具准备阶段为 0；PageIndex/OCR/图谱 mutation 为 0。

## 夹具 A 唯一任务

- taskId：`4cc88a6f-fe10-42eb-96ad-f1e229d84bca`。
- 创建接口：HTTP 201；请求仅发送一次。
- 任务总数 20 → 21；在途 0 → 1，终态后回到 0。
- 冻结版本：workflow/input/artifact 均为 `2.0`。
- 冻结范围：1 个测试材料叶子、2 个已审核功能，非固定 2N 规则。
- 终态：顶层 `PARTIAL`；处理状态 `FAILED`；覆盖状态 `UNABLE_TO_GENERATE`；artifact 存在但不作为成功验收制品。

## 失败和写入边界

- 2 个 `requirement-fact-extraction / REQUIREMENT_FACT_EXTRACTION_V2` 工作均失败。
- 固定失败外壳：`business_validation_failed / FACT_ATOMICITY_INVALID / $.requirement_facts[0].statement`。
- 两个失败窗口 accepted result hash 均为空；V2 正式事实与事实引文均为 0，证明整窗口零正式接收。
- 2 个 `functional-testcase-design / FUNCTIONAL_TESTCASE_DESIGN_V2` 工作完成，但持久化 outcome 均为 `unable_to_generate`，正式覆盖均为 false。
- 持久化计数：已审核功能 2、测试点 2、生成结果 2；事实 0、可测性反馈 0、正式/待确认用例 0、步骤 0、引用绑定 0。
- 旧 `requirement-material-quality-review`、`feature-scope-reconciliation` 及四个旧 operation 在该任务中的调用计数为 0。
- 处理状态和覆盖状态保持分轴，没有把部分完成伪装为 `COMPLETED`。

## 停止门禁与恢复条件

- 夹具 B 的 Java 创建 POST=0；任务 retry POST=0；未创建替代任务。
- 未执行页面双尺寸与 Excel 两 Sheet 成功验收，因为它们只能在两项任务均通过后作为同源成功证据，不能用失败制品补证。
- 当前证据只能证明：KEE 返回的事实结果被 Java 权威原子性门禁拒绝。未读取或保存模型正文，因此不能在本证据中武断判断是 KEE 输出确有复合事实还是 Java 原子性判定误拒。
- 安全恢复前置：以同一非业务夹具建立跨服务 TDD，证明 KEE 输出满足原子事实合同，或证明并最小修复 Java 的误拒；不得删除原子性门禁、不得手工改业务表、不得盲目 retry。修复门禁完成后，由主协调会话决定是否继续同一夹具任务或重新安排完整两项验收。

## 证据位置

- 运行明细：`backend/target/acceptance/tgv2-two-general-fixtures-20260830-173321/`
- 创建证据：`task-a-create-safe.json`
- 终态证据：`task-a-terminal-safe.json`
- 夹具准备证据：`fixture-preparation-safe.json`

所有安全证据均不包含材料正文、原始 KEE JSON、模型输出、内部证据键、提示词或凭据。
