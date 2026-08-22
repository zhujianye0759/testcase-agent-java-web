## Context

`prototype` 与 `requirement_list` 已由 Java 的范围快照、材料遍历和审查校验器处理：它们作为补充材料参与审查，但审查器拒绝其输出正式 requirement facts。新梅州知识库使用这两个类型，而旧验收脚本保存的是另一知识库和历史 `requirements_spec` 映射，不能被改写为新现场证据。

## Goals / Non-Goals

**Goals:**

- 把两个内部类型投影成稳定中文名称。
- 用范围解析和校验器测试证明五类材料同坐标冻结、补充材料不能成为正式来源，且旧范围仍能解析。
- 提供只读的新知识库范围核验工具，检查已上传材料后才允许后续人工创建任务。

**Non-Goals:**

- 不改 KEE、上传/删除材料、切换版本、创建任务或变更数据库。
- 不将 `requirements_spec` 从旧任务的兼容范围中移除，也不为特定知识库在 Java 中硬编码业务身份。

## Decisions

1. 前端在既有 `admissionTypeText` 映射中增加两个中文标签，而不在 API 返回中重写类型键；冻结快照和审计记录仍保留真实类型。
2. 使用动态范围 resolver 的五文档夹具证明类型、文档和版本坐标原样冻结；沿用 validator 的失败关闭边界证明 `prototype`、`requirement_list` 不能生成 formal fact。
3. 新建 ignored 验收目录的只读脚本，不修改历史 `meizhou-final-20260822` 证据。脚本仅读取新 KB 的文档清单，断言 V1.0、单一非空项目范围以及精确的 1+1+2+1 类型计数，并排除 `功能测试清单0715.xlsx`。
4. 全任务 `RequirementScope` 继续完整冻结所有已选材料。仅 `requirement-material-quality-review` 在每个材料切片调用前派生一个不可变单文档 Scope，因而其 `knowledge_ids` 和唯一 `system_scopes.knowledge_ids` 只含当前 `material.documentId()`；`material_key` 仍只作调用方关联，绝不与文档 ID 比较或绑定。功能清单提取、核对和用例设计继续使用既有 Scope。

## Risks / Trade-offs

- [新 KB 材料尚未解析完成] → task-options 暂不展示该范围，脚本仅报告未就绪，绝不构造替代范围。
- [旧任务依赖 requirements_spec] → 保留已有解析与正式来源规则，仅在新范围夹具中证明唯一 work_order_plan 正式来源。
- [脚本误操作现场] → 只提供 Status/AssertReady 读取动作，不包含上传、删除、切换 current 或 backfill 调用。
- [把全任务范围误当作单次审查授权] → 用协调器与 WireMock 分别锁住派生边界和实际 JSON；派生拒绝快照外文档，且不修改原快照。
