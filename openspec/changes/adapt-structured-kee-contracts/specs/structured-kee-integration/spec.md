## ADDED Requirements

### Requirement: [REQ-SKI-001] Java 完整枚举 KEE 持久化解析文本
Java SHALL 通过 `GET /api/v1/knowledge/{knowledgeId}/parsed-units` 从无游标首页开始逐页读取当前且解析完成的授权文档，并 SHALL 只接受冻结的成功外壳及 `knowledge_id`、`total_units`、`units`、`next_cursor`、`complete` 字段。Java MUST 验证全局唯一 `unit_id`、稳定 `(chunk_index, unit_id)` 顺序、连续 `ordinal=1..total_units`、逐页总数一致、游标无循环以及最终页 `complete=true` 且 `next_cursor=null`。

#### Scenario: 全部解析单元被确定遍历
- **WHEN** Java 从首页读取到最终页且所有顺序、唯一性、计数和终态不变量成立
- **THEN** Java SHALL 将该文档标记为“持久化解析文本完整遍历”
- **AND** SHALL NOT 将该结论表述为 Word 原生页码、PDF 版面或 Excel Sheet/行/列坐标完整

#### Scenario: 任一页或遍历不变量失败
- **WHEN** 任一页返回 `forbidden`、`document_not_ready`、`cursor_signing_unavailable`、`invalid_cursor`、`document_not_current`、`parsed_unit_integrity_error`、`unit_too_large`，或出现重复、遗漏、乱序、循环、总数变化或错误终态
- **THEN** Java SHALL 丢弃本次完整遍历的全部暂存结果
- **AND** SHALL NOT 保留为可用完整材料

### Requirement: [REQ-SKI-002] Java 只构造六字段同步结构化 Skill 请求
Java SHALL 只向 `POST /api/v1/agent-chat/{sessionId}/isolated-skill` 发送 `agent_id`、`skill_name`、`knowledge_base_ids`、`knowledge_ids`、`system_scopes` 和 `input`，序列化 body MUST 不超过 2 MiB。请求 SHALL 只包含一个 KB、1..100 个唯一文档和一个系统/唯一版本/非空项目 scope；scope 的 KB 与文档集合 MUST 与外层完全一致。Java MUST NOT 发送普通聊天、调用方 prompt/schema/model/tool、Web/MCP、history/Memory、附件、图片或 mention 字段，且 MUST NOT 在失败时回退普通 Agent Chat。

#### Scenario: Java 构造一个有界一致范围
- **WHEN** 一个工作项从已冻结任务范围调用一个受支持 Skill
- **THEN** Java SHALL 构造精确六字段请求并在发送前验证 KB、版本、项目和文档集合一致

#### Scenario: 本地范围不一致
- **WHEN** 请求候选范围包含多个 KB/scope/version、空项目、重复文档或内外文档集合不一致
- **THEN** Java SHALL 在网络调用前失败关闭

### Requirement: [REQ-SKI-003] Java 使用三个精确 Skill 输入合同
Java SHALL 只调用 `requirement-material-quality-review`、`feature-scope-reconciliation` 和 `functional-testcase-design`。材料审查 input SHALL 包含 `material_key`、受支持的 `content_type_key`、`source_label` 和 1..32 个唯一 `units`；每个切片首 `ordinal` MUST 大于等于 1，后续每项 MUST 等于前一项加 1，并 SHALL 保留 parsed-units 全局序号。

`feature-scope-reconciliation` input SHALL 以严格 `operation` 区分两种操作。`extract_function_list` SHALL 包含 `operation="extract_function_list"`、`material_key`、`source_label` 和 1..32 个功能清单 parsed units；切片的 `unit_key`、全局连续 `ordinal` 和 `content` 规则与材料审查完全相同。其 result SHALL 精确回显同一 operation，并返回 `function_list_items`；每项只含 `path`、`description` 和 1..100 个非空唯一 `evidence_keys`，MUST NOT 包含模型生成的 `item_key`。Java SHALL 先验证每项证据属于当前功能清单材料及当前切片，再生成任务内稳定 `item_key`，跨切片聚合并确定性去重。

`reconcile` input SHALL 包含 `operation="reconcile"`、1..200 个唯一功能清单条目和 0..200 个唯一需求事实；result SHALL 精确回显 `operation="reconcile"` 并返回 reconciliations。Java 与 KEE 均 SHALL 拒绝 input/result operation 错配并产生零业务结果。用例设计 input SHALL 包含一个功能和一个受支持类型、依据及缺失信息的测试点。不得新增第四个 Skill、复用普通 Agent/Markdown 扫描或由 Java 猜测 Excel 行列层级。

所有 key SHALL 为 1..128 个 UTF-8 字符；面向读者的标签和描述 SHALL 不超过 16,384 UTF-8 字节；key 数组 SHALL 最多包含 100 个唯一非空值；每个嵌套层级 MUST 拒绝未知字段。三个 input 的精确形状分别为：

```json
{
  "material_key": "string",
  "content_type_key": "requirements_spec|work_order_plan|prototype|requirement_list",
  "source_label": "string",
  "units": [{"unit_key":"string","ordinal":33,"content":"string"}]
}
```

每段 `content` SHALL 为 1..65,536 UTF-8 字节。

```json
{
  "operation":"extract_function_list",
  "material_key":"string",
  "source_label":"string",
  "units":[{"unit_key":"string","ordinal":33,"content":"string"}]
}
```

```json
{
  "operation":"reconcile",
  "function_list_items": [{"item_key":"string","path":"string","description":"string","evidence_keys":["string"]}],
  "requirement_facts": [{"fact_key":"string","function":"string","evidence_keys":["string"]}]
}
```

```json
{
  "function_key":"string",
  "function_name":"string",
  "test_point":{
    "test_point_key":"string",
    "type":"normal_behavior|input_validation|boundary_value|permission|state_transition|business_exception|dependency_failure",
    "description":"string",
    "requirement_fact_keys":["string"],
    "evidence_keys":["string"],
    "basis":"formal_requirement|general_experience",
    "missing_information":["string"]
  }
}
```

#### Scenario: 全局 ordinal 切片合法
- **WHEN** 材料审查切片的 ordinal 为连续的 `33..64`
- **THEN** Java SHALL 保留该全局序号并允许构造请求

#### Scenario: 切片断序或被重新编号
- **WHEN** 切片 ordinal 不连续，或 Java 试图把非首页切片重编号为从 1 开始
- **THEN** Java SHALL 拒绝该工作项且不调用 KEE

### Requirement: [REQ-SKI-004] Java 只接收统一同步 JSON 外壳
Java SHALL 只接收 `success=true` 且 `data.schema_version="1.0"`、`data.skill_name` 与请求相等、`repair_attempted` 为布尔值、`result` 与请求 Skill 类型相符的完整同步 JSON。Java MUST NOT 解析 SSE、Markdown、原始模型响应或工具证据，且 SHALL 对超过 4 MiB、未知字段、错误版本、Skill 错配或不完整响应失败关闭。

统一成功外壳 SHALL 精确为：

```json
{"success":true,"data":{"schema_version":"1.0","skill_name":"string","repair_attempted":false,"result":{}}}
```

`requirement-material-quality-review` 的 result SHALL 精确为：

```json
{
  "requirement_facts":[{
    "fact_key":"string","function":"string",
    "roles":["string"],"trigger_conditions":["string"],"inputs":["string"],
    "business_rules":["string"],"outputs":["string"],"permissions":["string"],
    "state_changes":["string"],"exception_handling":["string"],
    "external_dependencies":["string"],"evidence_keys":["string"]
  }],
  "review_findings":[{
    "finding_key":"string","issue_type":"string","description":"string",
    "evidence_keys":["string"],"test_design_impact":"string",
    "current_project_recommendation":"string",
    "design_center_guideline_recommendation":"string",
    "handling_level":"blocking|continue_incomplete|improvement"
  }]
}
```

`requirement_facts` 与 `review_findings` SHALL 各为 0..200 项且合计至少一项；各自 key SHALL 唯一。

`feature-scope-reconciliation` 的 result SHALL 精确为：

```json
{
  "operation":"extract_function_list",
  "function_list_items":[{
    "path":"string","description":"string","evidence_keys":["string"]
  }]
}
```

或：

```json
{
  "operation":"reconcile",
  "reconciliations":[{
    "reconciliation_key":"string",
    "function_list_item_keys":["string"],"requirement_fact_keys":["string"],
    "classification":"exact_match|function_list_only|requirements_only|conflict|duplicate|split|merge|insufficient_evidence",
    "evidence_keys":["string"],"scope_recommendation":"string",
    "confirmation_status":"confirmed|pending_confirmation"
  }]
}
```

`reconciliations` SHALL 为 1..200 项，且每项两个来源 key 数组至少一个非空。`extract_function_list` 与 `reconcile` 的 input/result operation MUST 逐字一致；operation 错配 SHALL 作为 `structured_output_invalid` 失败关闭且产生零结果。

`functional-testcase-design` 的 result SHALL 精确为：

```json
{
  "function_key":"string",
  "test_point_key":"string",
  "testcases":[{
    "case_key":"string","title":"string","preconditions":["string"],
    "steps":[{"step_no":1,"action":"string","expected":"string"}],
    "requirement_fact_keys":["string"],"evidence_keys":["string"],
    "case_status":"formal|pending_confirmation",
    "missing_information":["string"]
  }]
}
```

`testcases` SHALL 为 1..50 项；每条用例 SHALL 有 1..50 个步骤，`step_no` 从 1 连续。语义上必填的 result 字符串 SHALL 非空且不超过 16,384 UTF-8 字节，字符串数组 SHALL 最多 100 项。

#### Scenario: 同步结构化成功
- **WHEN** KEE 返回与请求 Skill 相符的完整 `1.0` 成功外壳
- **THEN** Java SHALL 将 typed result 交给业务 validator
- **AND** SHALL NOT 在业务校验前持久化结果

#### Scenario: 收到旧 SSE 或 Markdown
- **WHEN** 专用路径返回事件流、Markdown、代码围栏或普通 Agent 最终回答
- **THEN** Java SHALL 将其视为协议失败并产生零业务结果

### Requirement: [REQ-SKI-005] Java 按稳定错误类型失败关闭
Java SHALL 从既有失败外壳的 `error.details.type` 读取 `invalid_request`、`request_too_large`、`session_not_found`、`forbidden`、`unsupported_skill`、`skill_unavailable`、`model_unavailable`、`model_execution_failed`、`structured_output_invalid` 或 `response_too_large`。除 `repair_attempted` 外，Java MUST NOT 依赖其他 details 字段、原始响应、正文、凭据、URL 或栈来决定业务结果。

#### Scenario: KEE 返回稳定失败
- **WHEN** 专用调用返回任一稳定错误类型
- **THEN** Java SHALL 接收零业务结果并按固定本地策略记录终态或有界重试
- **AND** SHALL NOT 保存或展示敏感错误正文

### Requirement: [REQ-SKI-006] Java 集成状态不超过外部验收证据
Java OpenSpec 和消费者合同 MAY 在 KEE 字段冻结后实施，但真实集成、可运行和部署结论 MUST 等待 KEE 代码验收及精确可部署提交或镜像。规格 strict PASS MUST NOT 被表述为接口已部署。

#### Scenario: 仅有 KEE 冻结规格
- **WHEN** KEE OpenSpec 已 strict PASS 但代码尚未验收或部署
- **THEN** Java SHALL 只声明消费者设计/合同可启动
- **AND** SHALL 将真实联合验收保持为未完成
