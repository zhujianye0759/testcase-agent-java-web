## ADDED Requirements

### Requirement: Structured ALL detail uses the persisted structured projection

当任务详情包含 `structuredResult` 时，页面 SHALL 使用该对象显示处理状态、覆盖结果、材料遍历、需求审查、功能核对、用例设计、审查发现、核对结果和测试用例；页面 MUST NOT 使用 legacy `businessProgress`、Markdown 审查行或 Markdown 用例行冒充 structured 状态。 `[Req-ID]: REQ-STD-001`

#### Scenario: Completed task cannot hide unable coverage behind full delivery

- **WHEN** 顶层任务为 `COMPLETED`，structured processing 为 `COMPLETED` 且 coverage 为 `UNABLE_TO_GENERATE`
- **THEN** 页面显示“处理已完成”和“正式覆盖无法生成”，并且不显示“完整交付”或 legacy 阶段

#### Scenario: Structured findings and reconciliation are visible

- **WHEN** structuredResult 保存 4 条 review findings 和 1 条 reconciliation
- **THEN** 页面显示全部 5 条业务结果以及固定中文的核对分类和确认状态

### Requirement: Structured zero-testcase state is explicit

当 structured 处理已完成且 `testPoints` 为空时，页面 SHALL 显示与 coverage 一致的业务空状态；`UNABLE_TO_GENERATE` SHALL 明确表示没有已确认功能范围可用于生成正式用例，不得表示加载中、系统失败或完整覆盖。 `[Req-ID]: REQ-STD-002`

#### Scenario: Unable coverage has no formal testcase rows

- **WHEN** processing 为 `COMPLETED`、coverage 为 `UNABLE_TO_GENERATE` 且 testcaseDesign 为 0/0
- **THEN** 页面显示“无已登记工作”和未生成正式用例的原因，同时保留同任务 Excel 下载入口

### Requirement: Legacy detail remains compatible

当详情没有 `structuredResult` 时，FEATURE 和旧 Markdown 任务 SHALL 继续显示 legacy business progress、审查行、测试用例行及原下载行为。 `[Req-ID]: REQ-STD-003`

#### Scenario: Feature task without structured projection

- **WHEN** FEATURE 或旧任务详情只包含 businessProgress、auditRows 和 testCaseRows
- **THEN** 页面继续显示原业务进度和结果，且不渲染 structured 区域
