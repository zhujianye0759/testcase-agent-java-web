# PC UI 基础设计决策

## 适用范围

本文件记录 `REQ-WEB-007` 在测试用例智能生成 Web 端的 PC UI v1.0.0 基础映射。当前只覆盖桌面端语义 token 和页面壳，不定义移动端、暗色主题、业务组件或页面功能。

## 已采用规则

| 决策 | 本项目实现 | 权威标签 |
|---|---|---|
| 主色、中性色、成功/警告/错误色板 | `frontend/src/styles/tokens.css` 中的 `--color-*` | `source-confirmed` |
| 中文字体与 400/600 字重体系 | `--font-family-sans` 和 `--type-*` | `source-confirmed` |
| 0/4/8/16/24/32/40/48/56/64/72/80px 间距 | `--space-*` | `source-confirmed` |
| 2/4/8px 圆角 | `--radius-sm/md/lg` | `source-confirmed` |
| 12–44px、4px 递增的图标尺寸 | `--icon-size-*` | `source-confirmed` |
| 12/24 列网格语义 | `--grid-columns-simple/complex` | `source-confirmed` |
| 56px 顶部导航及其余页面壳尺寸 | `--shell-*` | `source-confirmed` |
| 基础壳选择 | `top/content`，暂不引入侧边栏和固定底部操作区 | `project-configurable` |
| 内容宽度达到 1200px 时边距/沟槽从 16px 同步切换为 24px | 命名 CSS 容器 `app-shell`，使用 `@container`，不把物理设备宽度当作断点 | `source-confirmed` + `ai-adjudicated-v1` |
| 内容容器保持流式 | 不设置产品最大宽度 | `ai-adjudicated-v1` |
| 可见键盘焦点 | 2px 主色轮廓、2px 偏移；作为当前项目无既有组件库时的本地选择 | `project-configurable` |
| 对话框层级 | `--layer-dialog: 1000`，仅用于原生确认对话框；后续浮层统一从命名 token 扩展 | `project-configurable` |

## 暂不确定的项目配置

以下内容在 PC UI v1.0.0 中没有可直接采用的精确值，本阶段不写入虚构 CSS：

- `shadow-base/middle/high/edge` 的 offset、blur、spread、颜色和透明度；待真实浮层/固定区组件出现后由前端负责人确认。
- `motion-fast/standard/complex` 的持续时间与 easing；当前页面没有需要动画的状态转换，只保留 reduced-motion 基线。
- 未来 Dialog/Drawer 以外的 overlay 层级；当前只有 `--layer-dialog`，后续同类组件必须扩展命名 token，禁止散落任意 `z-index`。
- 产品级最大内容宽度；当前采用流式容器，只有出现经过验证的跨页面复用需求才能新增全局 token。
- 正式无障碍合规目标；当前落实键盘可达、可见焦点和跳到主内容，但不在尚未完成全站审计时宣称达到某一完整标准。
- D-DIN-Pro 数字字体；未提供合法字体文件，因此不加载，继续使用系统字体回退。

## 验证基线

- 静态测试核对来源确认的 token 值，并检查 1200px 内容容器规则与可见焦点规则。
- 视觉检查使用 1440×820 设计检查画布，以及一个较窄和一个较宽的 PC 视口；1440×820 不作为固定产品尺寸。
- 后续页面仍需按各自任务补齐 loading、ready、empty、no-results、error、forbidden、not-found 等状态，不能由本基础切片虚构。

## 智能测试工作台视觉扩展

本节记录 `REQ-UIX-001` 至 `REQ-UIX-007` 的桌面端视觉决策。它不改变业务接口、任务状态或材料范围语义。

| 决策 | 本项目实现 | 权威标签 |
|---|---|---|
| 视觉方向 | 亮色企业底、深海军蓝导航与标题强调、受控青色作为智能工作流提示；网格和光晕仅作为不可交互背景 | `project-configurable` |
| 技术角色色 | `--color-tech-navy`、`--color-tech-cyan`、`--color-tech-grid`、`--color-tech-glow`；同时服务于导航、页眉、状态提示和背景 | `project-configurable` |
| 表面层次 | `--color-surface-raised/muted/inset` 用于页面、信息区和只读范围摘要，不以嵌套卡片作纯装饰 | `project-configurable` |
| 阴影层次 | `--shadow-base/middle/high` 仅用于悬停、导航、对话框和内容层级，参数不从截图反推 | `project-configurable` |
| 动效 | `--motion-fast/standard/complex` 用于短促交互反馈；`prefers-reduced-motion` 下移除过渡与环境装饰 | `ai-adjudicated-v1` + `project-configurable` |
| 内容容器 | 产品壳以 1440px 作为流式桌面内容最大宽度，1200px 内容阈值两侧边距和沟槽同步从 16px 切换到 24px | `project-configurable` + `source-confirmed` + `ai-adjudicated-v1` |
| 导航与业务上下文 | 顶部保留品牌、当前路由和“后台执行 · 任务可追踪”产品说明；受控青色短线仅为装饰，不使用成功色或声明实时服务健康状态 | `project-configurable` |
| 控制台式精修 | 在深色壳层之内保持亮色工作面；以低对比技术网格、光晕和分层表面强化任务聚焦。它们均为不可交互且不表达状态的背景，不能替代文字、标签、焦点或反馈 | `project-configurable` |

### 沉浸式深色指挥舱（REQ-UIX-009）

用户明确要求更酷炫、更具沉浸感的界面，本节按权威顺序以用户显式需求覆盖此前的亮色工作面选择；PC UI v1.0.0 不定义暗色主题，以下均为项目决策，不从截图反推。

| 决策 | 本项目实现 | 权威标签 |
|---|---|---|
| 深色壳层 | `--color-shell-abyss/deep` 作为页面基底，`color-scheme: dark` 让原生单选/复选/下拉/滚动条与主题一致，`accent-color` 染青；不用任何网格/纹理铺底 | `project-configurable` |
| 星空氛围 | 用户明确拒绝网格背景；改为 `.app-shell::before` 双尺度星点瓦片（`--color-star-bright/soft`，慢速透明度闪烁）+ `.app-shell::after` 流星（16s 周期内短暂划过天空，其余时间不可见）；标题带网格改为单次慢速流光扫过（`hero-sheen`）；全部纯装饰、不可交互、reduced-motion 静止（流星强制 `opacity: 0`） | `ai-adjudicated-v1`（用户评审确认方向） |
| 极光氛围 | `--color-aurora-cyan/violet/blue` 在 `.app-shell__ambient` 内模糊漂移；纯装饰、不可交互、不表达状态，`prefers-reduced-motion` 下静止 | `project-configurable` + `ai-adjudicated-v1` |
| 玻璃工作面 | `--color-glass-raised/muted/inset/active` + `--color-glass-border(-strong)`；主面板用半透明实色而非 backdrop 模糊以保住长表格滚动性能，模糊只用于壳层导航、hero 便签和对话框 | `project-configurable` |
| 深色文字 | `--color-ink-primary/secondary/tertiary/placeholder`，与玻璃底保持 ≥4.5:1 对比；来源确认的浅色板原样保留、不做反色推导 | `project-configurable` |
| 强调与渐变 | `--color-accent-cyan/blue/violet`、深端色、`--gradient-action-primary`（深青→深蓝，白字对比 ≥4.5:1）、`--gradient-text-heading`、`--gradient-edge-cyan`；每个操作区只有一个渐变主操作 | `project-configurable` |
| 深色状态色 | `--color-success/warning/error-ink/-glass/-edge`；状态始终同时有文字标签，进行中状态额外以圆点脉冲提示，不只依赖颜色 | `project-configurable` + `ai-adjudicated-v1` |
| 阴影与辉光 | `--shadow-base/middle/high` 改为黑基参数（海军蓝阴影在深色上不可见）；新增 `--shadow-glow-action/-cyan` 仅用于主操作与选中强调 | `project-configurable` |
| 交互反馈 | 悬停位移/描边提亮、加载扫光、进行中脉冲、页面入场均使用 `--motion-fast/standard/complex` 与 transform/opacity；reduced-motion 全部移除且状态仍可读 | `ai-adjudicated-v1` + `project-configurable` |
| 渐变描边复合写法 | 选中态以「半透明着色 + 不透明基底 `--color-glass-active`（padding-box）+ 渐变描边（border-box）」复合，避免透明填充透出描边层导致文字不可读 | `project-configurable` |
| 结构化布局语言 | 科技感由结构而非面板堆叠承载：顶层区域与表单 fieldset 去除边框/背景/角标，仅靠留白、细分隔线与青色自动编号图例（`counter-reset: form-step`）分层；框只保留给可选项、启动栏和告警 | `ai-adjudicated-v1`（用户评审确认方向） |
| 雷达主视觉 | 创建页 hero 放置一个纯装饰雷达（同心环 + 锥形扫描，`aria-hidden`，reduced-motion 静止），替代“用大面板当装饰” | `ai-adjudicated-v1` |
| 双栏控制台表单 | ≥1200px 容器阈值时表单拆为主列（方式、材料范围）+ 执行栏（策略、补充说明、启动动作），执行栏经 `display: contents` 包裹在不改变 DOM 顺序的前提下入右列 | `project-configurable` |
| 吸底启动栏 | 启动操作置于 `position: sticky` 玻璃条（层级 `--layer-sticky-actions: 10`），表单高于视口时始终可达；验收以滚动视口截图断言其不逸出视口 | `project-configurable` |
| 自定义控件同构 | 单选/复选/下拉保留原生元素与键盘行为，经 `appearance: none` 渲染深色面（发光单选点、青色勾、SVG 箭头）；`details` 内的对照复选框与材料复选框共享同一套样式，杜绝回退成全宽输入框 | `project-configurable` |
| 管道步进器 | 详情页业务流程渲染为节点圆点 + 连接细线（完成=实色成功点、当前=青色脉冲点、待办=暗色空心点），760px 以下折叠为左侧竖轨 | `ai-adjudicated-v1` |
| 参考级质感（Linear/Aceternity 手法） | 创建页标题升至 `--type-display-48` 加青色辉光（列表/详情保持 36px 维持层级）；选中方式卡片叠加 1px 流动光束描边（`@property --beam-angle` + conic 环形遮罩，不支持的浏览器回退为静态渐变描边）；选项卡/详情卡片/吸底栏加顶部径向内发光与 1px 内高光；极光增加反向漂移的品红层（`--color-aurora-magenta`）+ hero 两道静态光柱；主操作渐变缓慢流动（`action-flow`）；全部 transform/opacity/渐变位移动画，reduced-motion 静止 | `ai-adjudicated-v1` |
| AI 原生配色故事（ui-ux-pro-max 推理） | 产品定位为 AI 平台/开发者工具，按 skill 推荐采用 AI-Native 风格：AI 紫（`--color-accent-violet: #A78BFA` / `-deep: #6D28D9`）承担品牌时刻（雷达、步骤编号、选中卡片描边与光束、主操作紫→蓝渐变、标题渐变白→紫雾→青雾），青色保留给交互（焦点环、链接、信息条、状态脉冲）；极光紫/品红层加强；任务列表行 380ms 错峰上浮入场（`row-enter`，第 5 行后封顶）；离线内网部署不引入 Google Fonts，保留系统字体栈 | `ai-adjudicated-v1`（skill 数据驱动） |

### 页面状态与无障碍

- 启动页：材料范围加载、可用、空范围和创建失败均在本地表单区域表达；失败后保留选择和补充说明，并将焦点移到错误摘要。
- 动态材料范围：每个合格需求知识库在浏览器中作为一个`业务系统`呈现；不同时显示“知识库”和“系统”。页面只显示业务系统、版本和材料类型的业务标签。单一上级选项自动选择，多选项使用原生下拉框；材料类型使用可多选复选框。切换业务系统后立即清空无效的版本和材料选择，重新加载失败不清除功能描述或补充说明。
- 范围安全：页面只接收应用生成的匿名选项 ID 和可用文档数量，不展示 KEE 的知识库、版本、项目或文档 UUID；文档白名单由 Java 服务端在任务创建时重新读取并冻结。
- 列表页：保留加载、初始空、已加载、请求错误和分页恢复；本期没有服务端筛选合同，因此不伪造“无筛选结果”状态。
- 详情页：保留加载、已加载、局部加载错误、任务失败摘要、取消/重试确认与下载动作；状态标签同时显示文字。
- 全部可操作元素支持键盘访问和可见焦点；背景网格、光晕和按钮过渡均不承载业务信息，减少动态效果时会移除。
