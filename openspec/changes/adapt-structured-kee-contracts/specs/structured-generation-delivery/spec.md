## ADDED Requirements

### Requirement: [REQ-SGD-001] 页面只展示已验证并保存的结构化业务数据
任务详情 SHALL 只返回 Java 已通过业务校验并持久化的材料审查、功能核对、测试点、用例、处理状态和覆盖结果。页面 MUST NOT 展示或提供 KEE 原始 JSON、原始模型响应、Markdown 预览、Skill 正文、修复提示、凭据、URL、内部栈或技术范围标识。

#### Scenario: 用户查看已完成或失败任务
- **WHEN** 页面加载任务详情
- **THEN** 页面 SHALL 展示可读的已保存业务结果、状态和失败原因分类
- **AND** SHALL NOT 请求或渲染原始模型数据

### Requirement: [REQ-SGD-002] 处理进度与正式覆盖分别呈现
任务详情 SHALL 从已持久化的 structured work 与材料清单分别呈现材料遍历、需求审查、功能核对、用例处理进度和正式测试点覆盖结果；structured ALL MUST NOT 使用 legacy batch 或 legacy business progress 冒充这些阶段。structured processing wire enum SHALL 只接受 `PENDING|RUNNING|COMPLETED|FAILED|CANCELLED`，coverage wire enum SHALL 只接受 `PENDING|COMPLETE|PARTIAL|UNABLE_TO_GENERATE`；页面 SHALL 映射为“待处理、处理中、已完成、失败、已取消”和“待定、完整、部分完整、无法生成”，核对分类、测试点类型、确认状态和用例状态也 SHALL 映射为固定中文，不得回显未知原始枚举。`COMPLETED+PARTIAL` SHALL 表述为处理已完成且正式覆盖部分完整，不得改写为顶层 `PARTIAL` 或完整交付；取消 SHALL 只把实际完成的阶段标为完成，存在未完成阶段时将首个未完成阶段标为已取消/停止并保持后续阶段待处理；若取消或失败发生在四个阶段均已完成后的导出/完成门禁，四个阶段 SHALL 继续显示完成，仅由整体交付状态表示取消或失败。待确认经验用例 MAY 单独计数，但 MUST NOT 增加正式覆盖数量。加载、就绪、空、无结果、错误、禁止访问和未找到状态 SHALL 保持可区分。

#### Scenario: 只有待确认经验用例
- **WHEN** 某功能已有待确认经验用例但没有满足正式覆盖的用例
- **THEN** 页面 SHALL 显示候选已产生且正式覆盖仍不足

### Requirement: [REQ-SGD-003] Excel 固定为两个业务 Sheet
导出器 SHALL 只生成“需求与功能清单审查发现”和“测试用例”两个 Sheet。第一 Sheet SHALL 来源于已验证的材料审查与功能核对记录；第二 Sheet SHALL 来源于已验证的正式和待确认用例，并 SHALL 清晰区分其状态。导出器 MUST 保持确定顺序、公式注入防护、文件哈希和回读验证，且 MUST NOT 增加原始 JSON、Markdown 或技术证据 Sheet。页面与 Excel SHALL 读取同一份已验证持久化投影并保持业务记录数量一致；重复稳定 source ID SHALL 作为数据一致性错误失败关闭，MUST NOT 在导出时静默去重或少行。

即使最终功能集合或测试用例为零，只要任务按业务规则以 `COMPLETED` 终止，Java 仍 SHALL 从同一持久化投影生成并回读验证恰好两个 Sheet 的工作簿；两个 Sheet MAY 只有固定表头。`completeStructuredTask` MUST 拒绝空 artifact，MUST NOT 出现“无制品但处理成功”。

#### Scenario: 生成合法工作簿
- **WHEN** 任务满足允许导出的业务门禁
- **THEN** 工作簿 SHALL 恰好包含两个指定名称的 Sheet
- **AND** 所有行 SHALL 可追溯到 Java 已保存的结构化记录

#### Scenario: 未验证结果尝试导出
- **WHEN** 任一待导出记录未通过业务校验或引用绑定
- **THEN** 导出器 SHALL 失败关闭且不得发布制品

#### Scenario: 合法任务没有功能或用例行
- **WHEN** 所有计划工作合法终止但持久化投影没有测试用例行
- **THEN** 导出器 SHALL 生成并回读验证两个仅含固定表头的 Sheet
- **AND** 任务完成 SHALL 发布非空 artifact 元数据

### Requirement: [REQ-SGD-004] 交付物不得夸大解析证据与部署状态
页面、Excel 和验收记录 SHALL 将 parsed-units 结论表述为“持久化解析文本的完整、确定遍历”，不得表述为原始 PDF/Word/Excel 版面或坐标完整。KEE 代码未验收或未部署时，交付状态 MUST NOT 声称真实结构化接口联调通过。

#### Scenario: 只有消费者合同测试通过
- **WHEN** Java fixture 测试通过但 KEE 尚无已验收部署
- **THEN** 交付记录 SHALL 标记 Java 合同验证完成、真实联合验收待执行
