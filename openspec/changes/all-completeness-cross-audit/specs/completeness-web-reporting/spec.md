## ADDED Requirements

### Requirement: [REQ-CWR-001] 展示面向业务的完整性进度
任务详情 SHALL 使用业务标签和计数展示材料清单、双向审查、冻结功能和测试用例进度，而不暴露 KEE 文档 ID、单元 ID、游标、提示词或模型协议字段。

#### Scenario: ALL 任务仍在扫描需求
- **WHEN** 用户在需求扫描期间打开运行中的任务
- **THEN** 页面标识当前业务阶段和已处理/总数进度
- **THEN** 页面不得暗示最终功能总数已经冻结

### Requirement: [REQ-CWR-002] 区分完整、部分完成和失败的覆盖状态
任务列表和详情 SHALL 区分完整交付、审查完整但测试用例生成不完整，以及材料/审查完整性失败。

#### Scenario: 审查完成但一个功能无法生成
- **WHEN** 任务因证据不足或批次永久失败而处于 `PARTIAL`
- **THEN** 页面展示 `审查已完成，测试用例不完整` 及面向业务的原因
- **THEN** 页面不得展示通用的已完成措辞

#### Scenario: 材料清单无法证明完成
- **WHEN** 源遍历存在缺口、稳定遍历序号重复、游标失败、计数不一致或超大单元
- **THEN** 页面展示可恢复的材料审查失败消息
- **THEN** 页面不暴露技术游标或标识符

#### Scenario: 最终双向核对在生成批次前失败
- **WHEN** 已完成的材料扫描在最终双向核对中未满足严格完整性合同，且尚未创建生成批次
- **THEN** 任务详情返回安全的业务原因，说明功能范围未冻结
- **THEN** 页面不得仅显示空失败摘要，也不得暴露原始模型输出、提示词、候选标识或异常堆栈

#### Scenario: 无批次审查失败被重新排队
- **WHEN** 尚未创建生成批次的 `FAILED` ALL 任务开始新的审查尝试
- **THEN** 系统在原子转为 `QUEUED` 时清除上一次任务失败快照
- **THEN** 详情和列表在 `QUEUED` 及后续非失败状态不得投影旧失败摘要

### Requirement: [REQ-CWR-003] 对外详情、Markdown 和工作簿不泄漏机器绑定
任务详情 API、最终 Markdown 投影和两个 Excel Sheet SHALL 在交付边界确定性移除 `documentId=...`、`unitId=...`、`candidateIds=...`、`groupAnchorId=...` 机器绑定 token。系统 SHALL 保留可读的文档名称、版本、章节、页码、Sheet、行和摘录等已有 reader-facing 依据。若工作簿导出请求仍含上述 token，导出 SHALL 被拒绝，不能仅在页面隐藏。

#### Scenario: 内部候选结论进入任务详情
- **WHEN** 已接收的候选或审查结论包含单元绑定 token
- **THEN** 任务详情 API 返回的审查行与测试用例行均不包含该 token
- **THEN** 其他可读证据文本保持不变

#### Scenario: 工作簿输入仍有机器 token
- **WHEN** 两 Sheet 工作簿的任一单元格值包含 `documentId`、`unitId`、`candidateIds` 或 `groupAnchorId` 绑定
- **THEN** 导出器拒绝生成工作簿
- **THEN** 系统不得产生带隐藏机器标识的下载文件

### Requirement: [REQ-CWR-004] 安全观测最终双向核对页失败
系统 SHALL 在最终双向核对的单个功能审核批次耗尽固定三次尝试时，以类型化任务摘要仅保留 1-based 批次序号、总批次数、尝试次数和封闭失败类别。仅在批准类别集合 `MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE` 的三次累计命中（可混合）后进入逐目标复核，且其中一个单目标耗尽时，摘要 MAY 额外保留该批次内 1-based 目标序号与总数，且不得保留目标 ID。类别 SHALL 仅为 `STRICT_MARKDOWN`、`MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE`、`ANCHOR_CONFLICT`、`NORMALIZED_PATH_CONFLICT` 或 `UNKNOWN_CONTRACT`；分类 SHALL 只依赖既有固定校验消息。用户可见的安全摘要 SHALL 使用“第 X/Y 个功能审核批次”，并且不得将该术语表述为 PDF、Word 或 Excel 的源文件页；内部诊断日志 `page` 字段可保持技术命名。安全摘要 SHALL 写入既有 `generation_task.result_snapshot`。应用 SHALL 配置滚动持久诊断日志，以任务、阶段、页和尝试关联保留提示词、SSE 终态 Markdown 及校验失败上下文；不得将这些诊断内容投影到任务详情、列表或工作簿。日志必须在写入前删除 API Key、密码和鉴权头，且不新增数据库迁移。

类型化异常、任务快照、详情、列表和工作簿 SHALL NOT 保留或显示 candidateId、documentId、unitId、prompt、模型原文、URL、路径、secret、原始异常文本或原异常 cause。受控诊断日志可保留业务调试内容和内部坐标，但仍 SHALL NOT 保留或显示 API Key、密码和鉴权头。未知合同错误 SHALL 使用固定“固定合同未满足”摘要。

#### Scenario: 最终核对功能审核批次连续三次缺少目标
- **WHEN** 第 2/9 个功能审核批次连续三次未能覆盖其目标
- **THEN** 任务安全摘要为“最终双向核对第 2/9 个功能审核批次连续 3 次未通过：目标覆盖不完整”
- **THEN** 不冻结功能范围，也不持久化部分结论

#### Scenario: 逐目标补偿仍连续失败
- **WHEN** 第 2/9 个功能审核批次因批准类别累计命中进入逐项复核，且该批次第 3/8 个代表目标连续三次仍未通过
- **THEN** 任务安全摘要为“最终双向核对第 2/9 个功能审核批次第 3/8 个目标连续 3 次未通过：目标覆盖不完整”
- **THEN** 摘要不得包含任何 candidateId、documentId 或 unitId

#### Scenario: 未知原始合同错误包含敏感文本
- **WHEN** 页级校验原始错误文本包含 URL、secret 或机器坐标形态
- **THEN** 类型化异常、任务详情和列表仅显示页级固定摘要“固定合同未满足”
- **THEN** 上述原文可进入受控诊断日志，但 API Key、密码和鉴权头不得进入任何日志参数
