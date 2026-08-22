## ADDED Requirements

### Requirement: 补充材料类型安全展示并保持正式来源隔离
任务详情 SHALL 将冻结范围中的 `prototype` 显示为“界面原型图”、将 `requirement_list` 显示为“需求清单”，不得向读者回显内部类型键。动态范围解析 SHALL 保留同一知识库、系统、版本、项目和材料类别下每个已选材料的真实类型与文档白名单。`prototype` 和 `requirement_list` SHALL 仅作为补充审查材料；它们独立返回非空正式 requirement facts 时，Java SHALL 在任何正式事实、绑定、测试点、用例或正式覆盖写入前拒绝该审查结果。既有 `requirements_spec` 和 `work_order_plan` 任务快照 SHALL 保持可解析。

#### Scenario: 新梅州五类材料范围被冻结
- **WHEN** 用户选择同一坐标下的 function_list、work_order_plan、两个 requirement_list 和一个 prototype 范围
- **THEN** 任务快照保留五份文档及其精确类型，且 work_order_plan 满足正式需求材料前置条件

#### Scenario: 补充材料不能写入正式事实
- **WHEN** prototype 或 requirement_list 的审查结果包含 requirement fact
- **THEN** Java 拒绝整个结果，且不产生正式事实或下游正式覆盖

#### Scenario: 详情页展示中文类型
- **WHEN** 已保存任务范围含 prototype 和 requirement_list
- **THEN** 页面展示“界面原型图”和“需求清单”，不展示相应内部键

### Requirement: 新梅州知识库范围只读核验
验收工具 SHALL 只读核验知识库 `7c0f5fdd-980d-4389-8105-ec97f675dac1`、版本 `65d4779f-698f-42f3-a1ed-f7c2f694a624` 的材料清单。它 SHALL 要求五份预期文件处于 completed/enabled、共享单一非空项目范围，且类型精确为一份 function_list、一份 work_order_plan、两份 requirement_list 和一份 prototype。工具 SHALL 拒绝把 `功能测试清单0715.xlsx` 作为验收范围，并且不得发起上传、删除、版本切换、PageIndex 或任务创建请求。

#### Scenario: 仅在五份材料均就绪时通过
- **WHEN** 新知识库返回五份预期材料并且状态、范围和类型均匹配
- **THEN** 工具输出只读就绪摘要

#### Scenario: 材料尚未就绪时失败关闭
- **WHEN** 任一材料缺失、未完成、禁用、类型错误或项目范围不一致
- **THEN** 工具返回失败且不修改知识库或创建任务

### Requirement: 单材料审查授权范围
Java SHALL 保留任务全量 `RequirementScope` 的全部冻结材料白名单。对于每个 `requirement-material-quality-review` 材料切片，Java SHALL 从该快照派生不可变 Scope，使请求顶层 `knowledge_ids` 与唯一 `system_scopes.knowledge_ids` 均且仅包含当前 `material.documentId()`。Java SHALL NOT 将调用方 `input.material_key` 解释、比较或强制为 knowledge document ID。功能清单提取、功能范围核对和测试用例设计 SHALL 保持各自既有 Scope 合同。

#### Scenario: 审查材料键与文档 ID 不同
- **WHEN** 调用方传入不透明 `material_key`，并审查冻结快照中的一个文档
- **THEN** 审查请求只授权该文档 ID，且保留原始不透明材料键

#### Scenario: 其它 Skill 保持全量范围
- **WHEN** 同一全量任务快照继续进入功能清单提取、核对或测试用例设计
- **THEN** 其调用范围不因单材料审查授权而缩窄
