# Java V2 直接依据规则诊断代码门禁（2026-08-31）

## 结论

`FACT_DIRECT_EVIDENCE_UNSUPPORTED` 现在可在既有 V14 可空诊断列中保存有限、无正文的规则类别集合，帮助区分字面量、分句数量、约束前缀、内部缺口、边界省略、控制条件和词序或新增语义七类拒绝原因。公开 API、普通日志、页面和 Excel 仍只显示固定中文错误，不显示内部类别；既有不含类别的历史行继续兼容。本轮没有数据库迁移，也没有改变事实接收或拒绝的业务边界。

## RED 与 GREEN

- RED：在实现存储表示前，`RequirementFactV2ValidatorTest` 因缺少 `storageMessage()` 无法编译；实现后七类规则、多个引文的确定性合并及“任一引文能够支持即通过”均已覆盖。
- 安全 RED：构造未知持久化类别时，旧解析路径会把数据库原值保留在异常 cause 中；修复后解析器和详情读取均以固定无 cause 异常失败关闭。
- 聚焦单元/API/导出：107/107 通过。
- 真实 MySQL 原子接收与并发：单项门禁 2/2 通过；完整 `StructuredGenerationAcceptanceStoreIntegrationTest` 342/342 通过。
- 完整后端：973/973 通过，失败 0、错误 0、跳过 0。
- 前端：58/58 通过；ESLint 和生产构建通过。

## 静态门禁与复审

- `openspec validate realign-testcase-generation-v2 --strict`：通过。
- `git diff --check`：退出码 0；仅有工作树换行符提示。
- 新增生产行敏感字面量扫描：0。
- 测试新增行敏感字面量扫描：除固定脱敏测试哨兵外为 0。
- 规格轴复审：P0/P1/P2/P3 = 0/0/0/0。
- 标准与安全轴复审：P0/P1/P2/P3 = 0/0/0/0；复审发现的异常 cause 泄漏风险已按 RED→GREEN 修复后重新清零。

## 数据与运行边界

- 复用 V14 既有 `validation_error_code/path/message` 可空列；未新增或改写 Flyway 迁移。
- 接收失败时 attempt、work、task 的安全诊断与零业务接收在同一事务中提交；并发终结仍只有一个赢家。
- 没有调用 KEE，没有创建、重试、恢复或取消业务任务，没有修改 MySQL 或 Docker 生命周期。
- 部署、运行物身份和 live 零副作用资格门禁记录在 6.9e 完成证据中；6.10 的两套通用材料三态联合验收继续保持未执行。
