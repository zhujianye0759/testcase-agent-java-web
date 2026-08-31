# Java 6.1 完整回归历史失败审计

日期：2026-08-26
交接提交：`276d4abc9e04adee7f07670b87da001796c68f18`
当前分支：`codex/enforce-formal-testcase-grounding`

## 结论

- AFCE（可审计功能候选提取）新增回归：**0**。
- 交接提交历史基线失败：**3**。
- 当前完整后端回归：**623 项，620 通过，1 个断言失败，2 个执行错误，0 跳过**。
- 三项失败在交接提交的独立源码副本中以同一测试命令、同一失败位置和同一错误内容复现。
- 本审计没有修改生产代码或既有测试，也没有删除、放宽或跳过任何断言。
- 任务 6.1 是否可按“新增回归为零”完成，由 KEE 主会话决定；本证据不自行勾选 6.1。

## 可重复命令

当前工作树精确三项：

```powershell
.\mvnw.cmd '-Dtest=AllCompletenessGoldenFixtureTest#blocksFreezeWhenTheFullyDisposedGoldenLedgerStillContainsAnUnresolvedConflict+completesTheMappedGoldenFixtureWithStableNAndExactlyTwoNRows,MarkdownBatchPersistenceIntegrationTest#projectsOnlyTheSafeFinalReconciliationPageSummaryToTaskDetailAndList' test
```

交接提交基线使用 `git archive 276d4abc9e04adee7f07670b87da001796c68f18` 解压到忽略目录
`backend/target/acceptance/afce-6.1-baseline-276d4abc-20260826-0227`，在该副本执行完全相同的 Maven 命令。
这避免了对受保护脏工作树执行 reset、clean、checkout 或文件覆盖。

当前完整回归：

```powershell
.\mvnw.cmd test
```

结果：`Tests run: 623, Failures: 1, Errors: 2, Skipped: 0`。

## 逐项归类

### 1. `blocksFreezeWhenTheFullyDisposedGoldenLedgerStillContainsAnUnresolvedConflict`

分类：**c，提交基线本身失败**。

精确失败：测试在 `AllCompletenessGoldenFixtureTest.java:119` 调用旧材料审查后，
`FeatureAuditService.reconcileRepresentatives` 于 `FeatureAuditService.java:285` 抛出：

```text
FinalReconciliationPageException:
最终双向核对第 1/4 个功能审核批次连续 3 次未通过：固定合同未满足
```

直接原因：旧 deterministic golden agent 返回的最终核对 Markdown 连续三次未通过旧
`FeatureAuditService` 的固定核对合同，测试尚未到达其预期的 freeze 冲突断言。

基线证明：`FeatureAuditService.java`、该测试类及其 deterministic agent 相对交接提交均无差异；
交接提交的独立副本在相同调用栈、相同行号复现相同异常。

### 2. `completesTheMappedGoldenFixtureWithStableNAndExactlyTwoNRows`

分类：**c，提交基线本身失败**。

精确失败：测试在 `AllCompletenessGoldenFixtureTest.java:142 -> runCompletableFixture:164` 调用同一旧材料审查路径，
同样于 `FeatureAuditService.java:285` 抛出：

```text
FinalReconciliationPageException:
最终双向核对第 1/4 个功能审核批次连续 3 次未通过：固定合同未满足
```

直接原因和基线证明与第 1 项相同。失败发生在旧功能审查阶段，未进入
`extract_function_candidates`、候选审计三表、候选完成门槛或候选页面/Excel 投影。

### 3. `projectsOnlyTheSafeFinalReconciliationPageSummaryToTaskDetailAndList`

分类：**c，提交基线本身失败**。

精确断言：`MarkdownBatchPersistenceIntegrationTest.java:448`。

```text
expected: {"failureSummary":"最终双向核对第 2/9 个功能审核批次连续 3 次未通过：固定合同未满足"}
actual:   {"failureSummary": "最终双向核对第 2/9 个功能审核批次连续 3 次未通过：固定合同未满足"}
```

直接原因：测试把数据库 JSON 的空白排版当作字面量合同；实际序列化在冒号后包含一个空格，
而语义内容、页面安全摘要和敏感信息过滤断言均已通过。

基线证明：测试、`GenerationTaskRepository.failAuditingTask` 和 `asJson` 序列化方法的相关行均来自交接提交，
当前 AFCE 差异没有修改这些语句；交接提交独立副本复现完全相同的空格差异。

## 调用影响与两轴复审

- Standards 轴：0 findings。未发现 AFCE 差异能到达以上三个失败点，也未发现与其因果相关的硬标准违例或代码异味。
- Spec 轴：0 findings。规格明确保留旧提取、普通任务和 Markdown 语义；以上失败均发生在旧路径，不能作为 AFCE 缺失、范围扩张或错误实现。
- AFCE 聚焦协议、MySQL 原子接收、迁移、编排、状态、API、Excel 和前端门槛均保持既有 GREEN 证据。
- 共享 10 个 fixture 和 3 个固定哈希未修改。

## 建议

建议 KEE 主会话将 6.1 的判定口径明确为“相对交接提交新增回归为零”。若要求仓库绝对全绿，以上三项应另立旧路径修复任务；
不应在 AFCE change 内改写 golden fixture、放宽最终核对合同或把 JSON 断言改成只验证不抛异常来掩盖历史问题。
