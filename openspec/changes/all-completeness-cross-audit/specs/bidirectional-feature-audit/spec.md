## ADDED Requirements

### Requirement: [REQ-BFA-001] 以有界耐久工作扫描每个源单元
系统 SHALL 为每个已清点的功能清单和需求源单元创建有界、耐久的扫描工作，并 SHALL 为每个单元持久化一个终态处理结果。

#### Scenario: 材料扫描期间服务重启
- **WHEN** 一个源单元达到耐久终态前，其工作者租约已到期
- **THEN** 仅未完成的工作会再次变为可认领
- **THEN** 之前已接收的单元不会重复

#### Scenario: 单元候选结果绑定来源
- **WHEN** Java 请求一个单一源单元的中间候选表
- **THEN** 每个非空候选行必须保持精确四列，第三列仅为问题分类，第四列证据对照必须以 `documentId=<exact>; unitId=<exact>; ` 开头，第二个坐标 token 后以分号分隔证据正文
- **THEN** `unitId` 后直接粘连 `<br>`、坐标缺失/重复/错位、证据进入第三列或任何增删列均视为合同错误
- **THEN** 不匹配、缺失或跨单元的绑定使该工作失败关闭

### Requirement: [REQ-BFA-002] 通过第二遍校验需求提取
系统 SHALL 对每个需求源单元执行两遍有界语义扫描：第一遍提取功能候选项，第二遍识别第一遍未覆盖的功能性陈述。

#### Scenario: 第二遍发现额外功能
- **WHEN** 校验遍识别出第一遍中缺失且有证据支持的功能
- **THEN** 系统记录该新增需求候选项
- **THEN** 系统将该候选项纳入双向核对

#### Scenario: 需求扫描未收敛
- **WHEN** 有界的核对尝试无法为某一源单元产出稳定的候选项集合
- **THEN** 审查失败，而不是将该单元表示为已完整处理

### Requirement: [REQ-BFA-003] 对功能清单和需求候选项执行双向交叉审查
系统 SHALL 为每个功能清单和需求候选项给出恰好一个可追溯的终态结论，并 SHALL 保留全部支撑性的既有 KEE 文档/Chunk 证据引用。

最终结论每行的机器 token SHALL 为独立分号段，至少遵循 `documentId=<exact>; unitId=<exact>; candidateIds=id1,id2; <reader evidence>`；`candidateIds` 不得与 `<br>` 或说明文字粘连。

分类为“拆分”时，对象/功能点 SHALL 以 literal `<br>` 分隔至少两个互异纯文本业务路径；其他分类 SHALL 为单一纯文本业务路径且不得含 `<br>`。业务路径 MAY 使用单独的 `>` 作为可读层级分隔符；`<` 及任何由其开始的标记仍 SHALL 被拒绝，不能被冻结或导出。

对象/功能点 SHALL 只承载业务功能名或业务路径；章节号、条款号和目录编号 SHALL 只承载在证据对照列。对于固定错误 `Only SPLIT conclusions may contain multiple business paths`，下一次重试 SHALL 明确要求非拆分路径删除且不得复制证据中 literal `<br>` 后的章节或条款编号；系统 SHALL 不自动裁剪模型结果。单次该错误或同一功能审核批次三次未全部落在批准集合 `MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE` 时 SHALL NOT 逐项复核；只有三次累计均在该集合时，此业务路径结构错误才参与逐项复核。

同一行不同层级列的非空值 SHALL 按列顺序组成一个业务路径，值不同本身 SHALL NOT 构成冲突。冲突仅限同一层级或同一语义字段互斥，或跨正式材料对同一路径不兼容；无表头或层级语义不足 SHALL 处置为证据不足。

#### Scenario: 需求功能未出现在功能清单中
- **WHEN** 一个有证据支持的需求候选项未匹配任何功能清单候选项
- **THEN** 主审查记录 `功能清单遗漏`
- **THEN** 除非该候选项另行归类为证据不足，否则将其纳入最终功能集合计算

#### Scenario: 功能清单功能未出现在需求中
- **WHEN** 一个功能清单候选项未匹配任何需求候选项
- **THEN** 主审查记录 `需求未覆盖该功能点`
- **THEN** 不得静默丢弃该候选项

### Requirement: [REQ-BFA-004] 保留冲突、拆分、合并、重复和证据不足
系统 SHALL 为描述冲突、粒度不匹配、拆分功能、合并重复项、重复候选出现或重复解析文本出现，以及无法支持可执行测试用例的候选项保留基于既有 KEE 文档/Chunk 证据引用的原因和最终处置。

#### Scenario: 一个候选出现包含两个可独立测试的功能
- **WHEN** 审查结论认为一个复合候选出现必须拆分
- **THEN** 每个冻结功能保留既有 KEE 文档/Chunk 证据引用和拆分原因

#### Scenario: 证据无法支持可执行行为
- **WHEN** 一个候选项提供的正式证据不足以支持可执行的正向和反向测试用例
- **THEN** 主审查记录该证据缺口
- **THEN** 系统不得虚构业务规则以使该候选项变为可生成

### Requirement: [REQ-BFA-005] 仅冻结完整且可审计的功能集合
系统 SHALL 仅在每个清单单元和源候选项均有终态结果且不存在未解决关系后冻结最终功能集合。

#### Scenario: 一个源候选项仍无法说明
- **WHEN** 任一源候选项缺少终态结论
- **THEN** 系统不创建生成批次，也不报告审查完成

#### Scenario: 功能冻结后开始重试
- **WHEN** 后续生成尝试被重试
- **THEN** 其复用冻结功能标识、顺序、来源、可生成性和审查结论，而不重新发现

### Requirement: [REQ-BFA-006] 重领扫描工作时仅提供安全的格式纠错反馈
系统 SHALL 在同一材料审查工作重领时，从该工作此前已持久化的失败尝试读取最近一条失败摘要。第二次和第三次尝试 SHALL 始终附加一份固定、全面的严格 Markdown 格式基线：输出首字符为 `#`、第一行精确为 `## 需求与功能清单审查发现` 且其前无分析/说明/结论/引导语、整份输出仅含两张连续表且第一表最后一行后直接为 `## 测试用例`、不得输出思考/自我复核/重复表、精确标题/表头/分隔行、第一表四列、禁止 JSON/代码围栏、仅 `<br>`、无新增项时第一表零数据行、第二表零数据行且无尾随内容、以及精确的两坐标证据格式。仅当摘要匹配已知严格 Markdown 合同错误时，系统才可额外附加对应的固定重点提示；首次尝试 SHALL 不附加重领反馈；最多三次尝试、失败关闭和不可变审查台账语义 SHALL 保持不变。

失败摘要原文、异常文本、URL、文件路径、凭据或任何 `documentId`、`unitId`、`candidateIds` 的具体值 SHALL NOT 被注入模型提示词。全面基线 SHALL NOT 包含占位符或任意动态坐标值；它只可用自然语言指向本次提示已提供的坐标标记。未知、空或缺失摘要 SHALL 仅得到全面基线，不得附加原文或推测性重点；功能/需求扫描器和最终双向核对的既有严格 Markdown 合同 SHALL NOT 放宽。

#### Scenario: 同一工作第二次和第三次重领
- **WHEN** 同一材料扫描工作前一次因已知的严格 Markdown 标题或 HTML 合同错误失败，并被第二次或第三次认领
- **THEN** 该次提示词包含固定全面格式基线和对应的固定重点提示
- **THEN** 提示词不包含此前失败摘要的原文或任意敏感标识

#### Scenario: 未知失败摘要
- **WHEN** 重领工作此前保存的失败摘要不是白名单中的严格 Markdown 合同错误
- **THEN** 提示词只包含固定全面格式基线
- **THEN** 系统不得将原始失败文本作为模型上下文

### Requirement: [REQ-BFA-007] 有界最终核对保持全局语义并原子归并
系统 SHALL 将最终双向核对拆为每个功能审核批次最多 8 个代表目标的严格 Markdown 响应，但每个功能审核批次 SHALL 同时接收全量稳定候选作为比较上下文。Java SHALL 在调用模型前，以与冻结层完全相同的业务路径身份（NFKC、首尾 strip、连续空白折叠为一个空格并转为小写）对本批次候选的功能名称预分组；同一归一化路径只把全量顺序最早的成员作为模型代表目标。本批次代表目标区 SHALL 在同一行给出每个代表精确的 `candidateId`、`documentId`、`unitId` 和可读功能文本；模型 SHALL 逐字复制同一目标绑定行的三项机器坐标，且 SHALL NOT 从全量上下文中同名或同路径邻居复制 `documentId` 或 `unitId`。模型代表行必须是该归一化路径的单一路径非“拆分”结论，否则失败关闭。Java SHALL 将通过严格 parser 的代表结论确定性投影回该组每个成员：每个原始候选均得到恰好一行、自己的 `documentId`、`unitId`、`candidateIds`、稳定顺序以及仅来自该成员已持久化候选项的可读证据文本；代表模型行的可读证据不得被复制或归因到其他成员。投影复用同一结论类型、逐字业务路径和合法 anchor。若某路径已经有已接受的同路径单一路径结论，Java MAY 不再发起模型调用而直接按同一投影规则绑定本批次成员。不同归一化路径不得借此合并、补偿或重写。

投影后的每个功能审核批次第一表 SHALL 令每个原始目标候选恰好占一行，`candidateIds` SHALL 只含该目标自身，并 SHALL 有一个独立的 `groupAnchorId=<candidateId>` 内部 token。一个语义组 SHALL 选择全量候选顺序中最早的 candidateId 作为 anchor，anchor 自身 SHALL 自指；同一 anchor 的问题分类和对象/功能点路径 SHALL 逐字完全一致。单个“拆分”结论内的归一化路径 SHALL 互异。批次阶段在持久化前 SHALL 复用冻结层完整路径结构校验：非拆分不得含 literal `<br>`；拆分必须由 literal `<br>` 分隔至少两项，每项非空纯文本且归一化互异。Java SHALL 在既有严格 parser、代表投影及每个功能审核批次目标集合验证后验证 anchor、全局 union 和每个候选坐标，并按全量稳定候选顺序归并最终结论的序号、candidateIds 和证据。

每个功能审核批次最多 SHALL 尝试三次；用户可见的“功能审核批次”不是 PDF、Word 或 Excel 的源文件页，内部技术实现可继续使用 page 命名。仅当三次尝试的每一个封闭类别均属于批准集合 `MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE`（允许混合）时，系统 SHALL 丢弃全部三份未接收响应并按原稳定顺序对每个代表目标执行逐项隔离复核。任何一次 `STRICT_MARKDOWN`、`ANCHOR_CONFLICT`、`NORMALIZED_PATH_CONFLICT`、`UNKNOWN_CONTRACT` 或其他非批准类别 SHALL 使该批次直接失败关闭，且零逐项复核。每项仍 SHALL 使用全量候选上下文、精确目标绑定、既有 parser/path/anchor/cross-page 校验及最多三次尝试；所有项通过前 SHALL NOT 合并或持久化本批次，任一项失败 SHALL 使整个批次失败且不持久化部分结果。系统 SHALL NOT 自动改写模型分类、业务路径或来源坐标，且 SHALL NOT 降低冻结或 `2N` 门禁。严格 Markdown、目标覆盖、anchor、归一化路径或组一致性错误仅可追加固定且不含凭据的全面反馈和白名单重点。若当前批次的结构化回包已能确定其某一目标与已接受的同一路径结论发生分类、路径或 anchor 漂移，Java SHALL 为该目标追加仅由已接受结论派生的精确 `targetCandidateId`、分类、业务路径及 anchor 绑定，要求下一次逐字复用；不得从任意未接受回包复制值。全局归并失败或坐标缺失也 SHALL 失败关闭，且在所有批次和全局验证成功前 SHALL NOT 持久化任何最终结论。该批次化 SHALL NOT 将候选隔离为独立语义判断，也 SHALL NOT 放宽既有严格 parser、遗漏/冲突/重复/拆分/合并的全局处置或冻结门禁。

当同一功能审核批次内的归一化业务路径出现不同分类或 anchor 时，第二、三次 bulk 尝试 SHALL 为每个冲突组仅列出该组全部精确 `targetCandidateId`、按全量稳定候选顺序确定的 `earliestTargetCandidateId`、同一实际值的 `requiredGroupAnchorId` 及固定规则，并 SHALL 清晰分隔多个冲突组；反馈 SHALL NOT 回灌未接收回包的路径、分类或正文。固定规则 SHALL 要求模型：若仍判断为同一路径，每行 `groupAnchorId` 必须逐字复制该组 `requiredGroupAnchorId` 的实际值，且分类和完整路径完全一致；若判断不同，基于正式证据输出真实可区分的路径和各自合法 anchor。Java SHALL 仅验证，SHALL NOT 自动作出语义裁决或改写分类、路径、anchor。该 `NORMALIZED_PATH_CONFLICT` 仍为非批准类别，三次耗尽 SHALL 失败关闭、零单目标复核、零持久化；此前已接受跨批次先例继续按既有精确绑定规则处理。

当 `validatePageConclusions` 在最终 projected 当前行发现 `groupAnchorId` 指向已接受前批候选、但当前分类或完整业务路径不逐字等于该 accepted anchor 结论时，第二、三次 bulk 尝试 SHALL 仅为每个该当前目标输出精确 `targetCandidateId`、`rejectedGroupAnchorId` 与同一当前目标实际值的 `requiredSelfAnchorId`；反馈 SHALL NOT 回灌未接收回包的业务文本、分类、路径或正文。固定规则 SHALL 要求：若不逐字复用已接受先例区的分类和完整业务路径，`groupAnchorId` 必须逐字复制 `requiredSelfAnchorId` 的实际值；若确属旧语义组，必须逐字复用分类、完整路径和旧 anchor。Java SHALL 仅验证，SHALL NOT 裁决或改写任何模型分类、路径或 anchor。该 `ANCHOR_CONFLICT` 仍为非批准类别，三次耗尽 SHALL 失败关闭、零单目标复核、零持久化。

对于固定失败 `Grouped representative evidence must bind its exact documentId and unitId`，下一次重试 SHALL 仅追加固定、非敏感指令，重申逐字复制该代表目标绑定行的 `candidateId`、`documentId`、`unitId`，并禁止复制同名或同路径邻居的来源坐标；系统 SHALL 不自动重写模型坐标。该错误仅在同一批次三次累计均属批准集合时参与逐项复核。

#### Scenario: 一个匹配组跨越两个最终核对页面
- **WHEN** 第一个和后续页面的目标候选属于同一语义组
- **THEN** 两页均使用全量顺序最早候选的同一个自指 `groupAnchorId`
- **THEN** Java 以稳定顺序形成一条包含该组全部 candidateIds 和全部来源坐标的最终结论
- **THEN** 最终结论仅在全部页面通过后一次性持久化

#### Scenario: 同页重复业务路径只由一个模型代表裁决
- **WHEN** 本页两个或多个候选的功能名称按业务路径身份归一化后相同
- **THEN** Java 只将该组最早成员作为模型代表目标
- **THEN** 代表结论保持该路径并通过严格 Markdown、anchor 和坐标校验后，Java 为每个成员重建其精确 `documentId`、`unitId` 和 `candidateIds` 绑定
- **THEN** 每个成员仅保留自身已持久化的可读证据文本，不得把代表文档或单元的专属证据归因到该成员

#### Scenario: 同名邻居不能代替代表来源坐标
- **WHEN** 一个页面的代表目标与全量上下文中的同名或同路径邻居具有不同的 `documentId`、`unitId`
- **THEN** 本页目标区为代表逐字给出自身三项机器绑定，重试也重申不得复制邻居坐标
- **THEN** 邻居坐标的代表回包被严格拒绝；仅自身坐标的更正回包可通过。单次代表绑定错误或三次未全部落在批准集合时不得逐项复核；只有同一功能审核批次三次累计均在批准集合时才参与逐项复核
- **THEN** 任何代表路径漂移、代表“拆分”结论、代表坐标错误或不同路径候选缺失均失败关闭，不得将其投影为已覆盖

#### Scenario: 后续页面格式错误或 anchor 非法
- **WHEN** 后续页面三次均不能满足严格 Markdown、目标覆盖或 anchor 合同
- **THEN** Java 不持久化此前页面已解析的任何最终结论
- **THEN** 错误消息和重试提示不回显原始模型输出或异常

#### Scenario: 非拆分路径附带证据章节号
- **WHEN** 一个非“拆分”结论在对象/功能点列将业务功能名与证据中的 `<br>` 后章节号拼接
- **THEN** Java 拒绝该页，并在下一次固定重试提示中要求章节号仅保留在证据对照列、删除该非拆分路径的 `<br>` 加编号
- **THEN** Java 不裁剪该模型结果；单次该业务路径结构错误或三次未全部落在批准集合时不得逐项复核，只有同一功能审核批次三次累计均在批准集合时才参与逐项复核

#### Scenario: 后续页面重判已接受业务路径
- **WHEN** 当前页结构化结论将已接受的同一归一化业务路径改为其他问题分类、路径文本或 `groupAnchorId`
- **THEN** Java 保持该结论拒绝，并向下一次尝试逐项提供已接受结论派生的精确目标绑定
- **THEN** 仅逐字复用该绑定的回包可通过；任何原始异常或未接受模型文本不得注入重试提示

#### Scenario: 功能审核批次批准类别累计命中时逐项复核
- **WHEN** 一个八项代表目标功能审核批次连续三次的每一个封闭类别均属于 `MISSING_TARGET`、`REPRESENTATIVE_BINDING`、`BUSINESS_PATH_STRUCTURE`，其中纯 `MISSING_TARGET` 是允许的特例
- **THEN** Java 丢弃该批次三份未接收响应，并按批次原始稳定顺序分别对八个代表目标执行单目标隔离核对
- **THEN** 每一项均须通过原有全量候选上下文、精确绑定、Markdown、路径、anchor 和跨批次校验，全部通过后才原子合并该批次
- **THEN** 任一项失败、任一非批准失败类别或任何部分结果均不得持久化、冻结或降低 `2N` 门禁
