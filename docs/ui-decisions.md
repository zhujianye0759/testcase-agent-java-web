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

### 页面状态与无障碍

- 启动页：材料范围加载、可用、空范围和创建失败均在本地表单区域表达；失败后保留选择和补充说明，并将焦点移到错误摘要。
- 动态材料范围：浏览器只显示知识库、系统、版本和材料类型的业务标签。单一上级选项自动选择，多选项使用原生下拉框；材料类型使用可多选复选框。切换上级后立即清空无效下级选择，重新加载失败不清除功能描述或补充说明。
- 范围安全：页面只接收应用生成的匿名选项 ID 和可用文档数量，不展示 KEE 的知识库、版本、项目或文档 UUID；文档白名单由 Java 服务端在任务创建时重新读取并冻结。
- 列表页：保留加载、初始空、已加载、请求错误和分页恢复；本期没有服务端筛选合同，因此不伪造“无筛选结果”状态。
- 详情页：保留加载、已加载、局部加载错误、任务失败摘要、取消/重试确认与下载动作；状态标签同时显示文字。
- 全部可操作元素支持键盘访问和可见焦点；背景网格、光晕和按钮过渡均不承载业务信息，减少动态效果时会移除。
