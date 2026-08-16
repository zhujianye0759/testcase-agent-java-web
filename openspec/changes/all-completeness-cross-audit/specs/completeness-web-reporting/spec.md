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
