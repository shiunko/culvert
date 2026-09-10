# Web 前端视觉与布局完全重构方案

## 1. 文档目的

`docs/11-Web前端页面完全重构计划.md` 处理的是协议、安全和分层问题。本文单独处理用户报告的现象——“提交时布局会错位”——并给出**不保留任何现有前端代码**、仅要求与现有后端契约兼容的视觉/布局完全重构方案。

结论先行：错位不是个别 CSS 写错了，而是当前前端选择的**渲染管线**本身会在每次 HTMX 换出内容时产生不确定的样式注入延迟。重构必须先换掉这条管线，再谈组件和视觉风格，否则任何新样式都会继承同样的错位问题。

## 2. 现状根因分析

### 2.1 浏览器运行时编译 CSS 是错位的主因

当前页面加载两份样式资源：

- `daisyui.css`：预构建但**未做任何 tree-shaking** 的完整 daisyUI 5.5.22，未压缩前体量约 970KB，内含所有组件和主题变体。
- `tailwindcss.js`：`@tailwindcss/browser@4.3.0`，即 Tailwind 的**浏览器内 JIT 引擎**，通过 `MutationObserver` 监听 DOM 变化，发现新的 utility class 组合后才现场编译并注入 `<style>`。

引用位置：[views.clj:677-680](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L677-L680)、[views.clj:1061](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L1061)。

这意味着：**首次出现的类组合永远有一段“无样式期”**。而 HTMX 换出内容恰好是最容易触发“首次出现”的场景。典型例子：

- 空状态用单行文字：`[:div.text-center.py-8.opacity-50 ...]`（[views.clj:330](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L330)、[views.clj:465](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L465)）；
- 真实内容用完全不同的卡片网格：`[:div.card.bg-base-300.shadow-md.mb-3 ...]`（[views.clj:332](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L332)、[views.clj:466](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L466)）。

用户第一次提交创建表单时，DOM 从“单行文字”换成“卡片网格”，卡片网格里的类组合此前从未在页面上出现过，Tailwind JIT 需要现场编译注入，中间必然经历一次“无样式 DOM → 有样式 DOM”的跳变——这正是“提交时布局会错位”的直接成因，不是网络延迟或后端问题。

### 2.2 缺乏尺寸预留，二次放大错位感

- 面板容器（`forward-tbody`、`docker-tbody`、`smolvm-tbody` 等）没有 `min-height`，内容量变化直接顶动整页高度。
- 没有骨架屏，`hx-get` 发出后到内容返回之间是空白，返回后瞬间撑开，叠加 2.1 的样式注入延迟，视觉上表现为“抖两次”。
- OOB 响应一次可能同时替换 4-5 个面板（[views.clj:625-647](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L625-L647)），其中位于视口外的面板高度变化会导致浏览器矫正滚动位置，用户感觉“页面跳了一下”。

### 2.3 缺少过渡动画，状态切换是硬切

- `hx-swap="innerHTML"` 未搭配 `transition:true` 或任何 CSS 过渡，内容是整块替换，没有渐入渐出；
- `<details>` 展开无动画，展开瞬间挤压下方内容；
- 表单校验失败、按钮 loading 态目前没有统一反馈，用户不确定点击是否生效，容易重复点击导致内容再次跳变。

### 2.4 结构性问题（影响可维护性，间接影响一致性）

- 12 栏 Tailwind 网格在 5 个表单中各写一份（[views.clj:709](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L709)、[views.clj:780](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L780)、[views.clj:854](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L854)、[views.clj:1002](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L1002)），列数、间距、标签结构各自实现，容易出现细微不一致；
- `views.clj` 单文件超过 1000 行，一个函数里混渲染结构、i18n 取值和状态判断；
- 功能性按钮大量依赖 emoji 字符（`▶️`⏸️`🗑️`），不同系统/字体渲染宽度不同，flex 行内高度和对齐会出现细微错位；
- `public/main.js` 是单文件全局事件委托，样式和行为没有组件边界，新增一个组件需要同时改三处不相关代码。

## 3. 设计目标

1. **零运行时样式编译**：构建期产出最终 CSS，浏览器不做任何 JIT，消除错位根因。
2. **零意外布局跳动**：所有异步内容有骨架屏或尺寸预留，CLS（Cumulative Layout Shift）不可感知。
3. **组件化前端**：Hiccup 侧和 JS 侧都以“组件”为最小单元，一个组件一个渲染函数/一个行为模块，接口简单清晰。
4. **视觉饱满但克制**：深色科技感 dashboard，卡片、渐变强调色、玻璃拟态点缀、语义化状态色，动效精致但不喧宾夺主。
5. **操作简单**：关键操作 2 步内完成，危险操作统一二次确认组件，表单反馈即时。
6. **后端零改动或改动可控**：不修改 `handlers.clj` 业务逻辑；如需调整 DOM id/资源协议，必须在同一批次同步 `route-dispatcher`、`fragment-registry`、`panelRefreshMap` 和 `main.js`，不允许中间态（沿用 [AGENTS.md 修改守则](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/AGENTS.md#L64-L68)）。

## 4. 视觉设计系统

### 4.1 主题基调

- 暗色为默认主题：深灰蓝背景（`base-100/200/300` 分层），强调色使用双色渐变（主色→辅色），用于关键 CTA 按钮、进度条、页面标题下的装饰线。
- 语义色严格对应状态，不与品牌色混用：

| 语义 | 用途 | 视觉 |
|---|---|---|
| success | 运行中、成功 toast | 绿色系 |
| warning | 异常、降级、待处理 | 黄色系 |
| error | 停止、失败、危险操作 | 红色系 |
| info | 中性提示、链接 | 蓝色系 |
| neutral | 已停用、未知 | 灰色系 |

- 图标统一改用本地 SVG sprite（裁剪自开源图标集的子集，随构建产物打包），emoji 仅保留在装饰性、非功能位置（如空状态插图），不再用作按钮唯一含义来源。

### 4.2 设计令牌（构建期生成 CSS 变量，不在浏览器计算）

```css
:root {
  --radius-sm: 0.375rem;
  --radius-md: 0.625rem;
  --radius-lg: 1rem;
  --space-1: 0.25rem; /* ... 到 --space-8 */
  --shadow-card: 0 1px 2px rgb(0 0 0 / 0.3), 0 8px 24px -8px rgb(0 0 0 / 0.35);
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --duration-fast: 120ms;
  --duration-base: 200ms;
  --duration-slow: 320ms;
}
```

所有组件样式引用这些变量，禁止在组件 CSS 里出现魔法数值。

### 4.3 排版

- 界面文字使用系统字体栈；端口号、容器 ID、命令、IP 等一律使用等宽字体，并统一 `tabular-nums`，避免数字宽度不一致造成的列错位。

## 5. 布局架构（直接对应第 2 节根因逐条修复）

| 根因 | 对应修复 |
|---|---|
| 浏览器运行时编译 CSS | 构建期用 Tailwind CLI 产出单一 `app.css`，随 `build.clj` 复制到 `target/classes/public`，浏览器只加载编译好的静态 CSS，不再引入 `@tailwindcss/browser` |
| 完整未裁剪 daisyUI | 改为按用到的组件裁剪，或直接吸收进自建组件 CSS，体量控制在个位数 KB 级别（gzip 后） |
| 面板无尺寸预留 | 每个面板容器声明 `min-height`，首次 `hx-get` 前渲染等高骨架屏 |
| 硬切换无动画 | `hx-swap="... transition:true"` 配合 CSS `@starting-style` 做渐入渐出；不支持的浏览器自动降级为直接替换，不报错 |
| OOB 连带跳动 | 采用第 5.1 节的 Tab 布局后，非当前激活 Tab 天然不可见（`hidden` 属性），刷新直接原地更新即可，不需要滚动或动画，也不需要额外的可见性探测逻辑 |
| 表单栅格各写一份 | 抽成统一 `ui/form-grid` 组件，列数配置化 |
| emoji 尺寸不一致 | 按钮统一用 SVG 图标 + 固定 `width/height`，emoji 退出功能按钮 |
| `<details>` 无动画 | 换成基于 `grid-template-rows` 过渡的 Disclosure 组件 |

### 5.1 App Shell：以 Tab 为唯一导航层级

不再用“以往可折叠的 Sidebar + 单页锚点”——这种结构本质上还是“把所有功能堆在一页里”，只是加了一个快速跳转入口。新设计直接把页面拆成互斥的 Tab，**同时只渲染/显示一个 Tab 的内容**：

```text
┌───────────────────────────────────────────┐
│ Topbar：Logo / 配额摘要 / WS 状态灯 / 用户菜单        │
├───────────────────────────────────────────┤
│ Tab Bar：总览 | 端口转发 | 反向代理 | 容器 | 虚拟机 | 管理员  │
│          ────────── ← 滑动高亮条（transform，不引发 reflow） │
├───────────────────────────────────────────┤
│ Tab Panel（仅当前激活项可见，独立滚动容器）          │
│  其余 Tab 的 DOM 用 `hidden` 属性隐藏但保留（不销毁）    │
└───────────────────────────────────────────┘
```

一级 Tab 固定为以下 6 个（管理员 Tab 仅 `is-admin?` 时渲染，普通用户完全不下载其 HTML/数据）：

| Tab | 定位 |
|---|---|
| 总览 | 默认首屏，只读，不包含任何 mutation |
| 端口转发 | Forward 列表 + 创建 |
| 反向代理 | Caddy 列表 + 创建（IP 直连模式下隐藏整个 Tab） |
| 容器 | Docker 列表 + 创建 |
| 虚拟机 | SmolVM 列表 + 创建（未启用时隐藏） |
| 管理员 | 用户/配额、主机容器分配（同一 Tab 内用二级 Segmented Control 切换，不再拆成新的一级 Tab） |

一级 Tab 数量控制在 6 个以内，是为了保证 Tab Bar 可一眼扫完，不再引入二级就拆新的一级 Tab。

Topbar 使用 `position: sticky`，与 Tab Panel 的滚动完全隔离：Tab Panel 内部任何高度变化都不会影响 Topbar/Tab Bar 布局，避免“刷新一个面板导致整页跳动”。

### 5.2 Tab 导航行为规范

- **可访问性**：严格按 WAI-ARIA Tabs 模式实现——Tab Bar 为 `role="tablist"`，每个 Tab 按钮为 `role="tab" aria-selected aria-controls`，内容区为 `role="tabpanel" aria-labelledby`，支持方向键切换。
- **隐藏不销毁**：切换 Tab 时用原生 `hidden` 属性切换可见性（而不是重新请求或卸载 DOM），已加载的 Tab 再次激活时零延迟、零重排列。
- **首次惰加载**：默认/上一次激活的 Tab 在首屏 HTML 中就给出真实内容（避免二次网络往返），其余 Tab 只给骨架屏容器，用户首次切换到这些 Tab 时才 `hx-get` 对应 `/panel/*` 端点，完全复用现有面板接口，不新增后端路由。
- **URL 可深链**：当前 Tab 写入查询字符串 `?tab=docker`，使用 `history.replaceState` 同步，刷新/回退/书签都能回到同一 Tab；默认值为 `overview`。这一层逻辑单独放在 `tabs.js`，不引入 SPA 路由库。
- **与刷新协议共存**：WebSocket `refresh` 事件仍排向所有 `panelRefreshMap` 登记的 DOM id，不区分当前 Tab 是否激活——非激活 Tab 在隐藏状态下安静更新，用户切换过来时数据已经是新的，不需要再次请求。

### 5.3 单个 Tab 内部结构模板（直观、操作简单的核心）

每个资源类 Tab（端口转发/反向代理/容器/虚拟机）内部都用同一套模板，降低学习成本：

1. **Tab 标题行**（紧减，不占据大块面积）：左侧标题 + 一行说明（如“端口转发 · 将本机端口映射到内网服务”），右侧配额徽章（如 `3/10`）+ 主操作按钮“+ 新建”。
2. **创建表单改为 Drawer/对话框，不常驻页面**：点击“+ 新建”才弹出一个从右侧滑入的 Drawer（窄屏下自动变成全屏 sheet），而不是现有方案里常驻在页面顶部的 12 栏大表单（[views.clj:709](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L709) 等处的常驻创建表单是当前页面“看上去很满”的主要来源）。Drawer 内字段分基础/高级两级，默认只显基础 3-4 个字段，高级选项（如 SmolVM 的 `allowHosts`/`allowCidrs`）收进可展开区域。提交成功后 Drawer 自动关闭，新卡片带 fade+scale 动画插入列表顶部，同时弹 toast。
3. **内容区**：固定为 `resource-grid`（卡片）或 `ui/table`二选一，同一资源类型不同时切换。空状态用居中插图 + 一句话说明 + “立即创建”按钮（直接打开 Drawer，不需要用户再去找入口）。
4. **次要信息收进 Popover**：现有页脚常驻的 socat/iptables 说明、端口范围规则（[views.clj:830-834](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L830-L834)）改为标题旁的“?”图标 Popover，默认不展示，按需查看。
5. **危险操作**：统一复用第 7 节的 `confirm-dialog`。

#### 总览 Tab 的专属结构

总览 Tab 不套用上述模板（它不创建任何资源，只导航+展示），结构为：

1. 顶部欢迎语 + 配额摘要（环形/条形进度，一目了然当前用量）；
2. 资源数量摘要卡片（转发数/代理数/容器数/VM 数），每张卡片可点击，点击直接激活对应 Tab（`data-action="tabs.activate" data-tab="docker"`）；
3. （可选渐进增强）最近变化列表，缺失不影响其余部分。

总览 Tab 不包含任何 mutation，加载最快，适合作为默认首屏。

### 5.4 资源展示统一为响应式卡片网格

Docker、SmolVM、以及未来任何新运行时资源，共用同一个展示组件：

```text
<div class="resource-grid" data-panel="docker">
  <!-- 每个资源一张 resource-card，CSS Grid auto-fill，宽屏 3 列/中屏 2 列/窄屏 1 列 -->
</div>
```

表格只用于强对齐数据场景（管理员用户配额表、主机容器分配表），本身也统一为 `ui/table` 组件，窄屏下同一份数据自动转为堆叠卡片（CSS 控制显隐，不重复模板，避免两份 HTML 不一致）。

### 5.5 骨架屏规则

- 任何 `hx-get` 触发的面板，首次渲染必须带一个**尺寸与真实内容同量级**的骨架占位（灰色渐变闪烁块），而不是空白或 loading 文字；
- 骨架屏的卡片数量按“上一次已知数量”或固定 2-3 个占位渲染，避免 1 变多的剧烈跳动。

## 6. 组件体系

### 6.1 服务端渲染组件（Hiccup）

新增 `src/culvert/web/ui/` 命名空间，每个组件是一个只接收数据、不读取领域状态的纯函数：

```text
src/culvert/web/ui/
├── shell.clj      ; app-shell, topbar
├── tabs.clj       ; tab-bar, tab-panel（渲染 role/aria 属性，切换逻辑在 JS）
├── drawer.clj     ; drawer（创建表单容器，桌面右侧滑入/移动端全屏 sheet）
├── card.clj       ; card, stat-card, resource-card
├── table.clj      ; responsive-table
├── badge.clj      ; status-pill（语义状态→图标+色板的唯一映射表）
├── form.clj       ; form-grid, field, button
├── modal.clj      ; dialog, confirm-dialog, popover
├── toast.clj      ; toast-region（服务端只负责渲染容器，动画交给 JS）
├── skeleton.clj   ; skeleton-card, skeleton-row
└── empty.clj      ; empty-state
```

组件函数签名示例：

```clojure
(status-pill {:state :running})
;; => [:span.pill.pill-success [:svg.icon ...] "运行中"]

(resource-card
  {:id "..." :title "..." :status :running
   :meta [["镜像" "alpine:3.21"] ["资源" "1C / 512m"]]
   :credentials {:username "..." :endpoint "/docker/credentials"}
   :ports [...]
   :actions [{:label "启动" :icon :play :hx-post "/docker/start" ...}]})
```

Docker 和 SmolVM 视图只需把各自领域数据转换成上述统一 DTO 传给 `resource-card`，不再各写一份几百行的卡片模板。

### 6.2 客户端行为组件（ESM，`public/app/`）

```text
public/app/
├── boot.js         ; 初始化顺序、模块装配
├── http.js         ; fetch 封装：CSRF、错误处理
├── toast.js        ; 挂载/动画/自动消失（textContent 写入，不用 innerHTML）
├── websocket.js     ; 连接、重连、事件分发
├── tabs.js          ; Tab 切换、URL 同步（?tab=）、hidden 属性管理、首次懒加载
├── panels.js        ; 面板挂载、骨架屏切换、非激活 Tab 静默刷新
├── drawer.js        ; Drawer 开关、焦点管理、提交成功后自动关闭
├── forms.js         ; loading 态、防重复提交、成功后 reset
├── credentials.js    ; 凭据显隐与复制
├── logs.js          ; 日志弹窗生命周期
├── modal.js         ; dialog 开关、焦点管理、confirm/popover 组件
└── motion.js         ; View Transition 封装 + prefers-reduced-motion 判断
```

所有模块通过 `data-*` 属性和自定义事件通信，不直接依赖 DOM id 字符串硬编码到多处，例如：

```html
<button data-action="credentials.reveal" data-endpoint="/docker/credentials" data-id="...">
```

`boot.js` 里维护一个 `data-action → handler` 的注册表，新增交互只需登记一条映射，不需要改动全局 click 监听器内部逻辑。

## 7. 动效与反馈规范

| 场景 | 行为 |
|---|---|
| 按钮点击（mutation） | 立即置为 loading 态（spinner + 禁用 + `aria-busy="true"`），响应返回后还原 |
| 表单提交成功 | 目标区域淡入 + 轻微上移（`translateY(4px)→0`），toast 提示 |
| 表单提交失败 | 字段级错误提示 + 表单容器轻微 shake（`prefers-reduced-motion` 时跳过位移，只保留颜色变化） |
| 卡片新增 | fade + scale-in（0.96→1） |
| 卡片删除 | fade-out + 高度收缩动画（避免其余卡片瞬间跳位） |
| 危险操作 | 统一 `confirm-dialog` 组件替代原生 `hx-confirm`（原生 `confirm()` 保留作为无 JS 兜底） |
| 面板刷新（WS 触发） | 内容淡出旧值、淡入新值，不做整体位移，避免用户正在操作时被“弹走” |

所有动效时长使用第 4.2 节的 token，且统一在 `prefers-reduced-motion: reduce` 时降级为无位移的纯透明度变化。

## 8. 响应式与可访问性

- 断点：`sm 640 / md 768 / lg 1024 / xl 1280`，与 Tailwind 默认断点保持一致，减少心智负担；
- 移动端 Tab Bar 保持同一种导航形态（横向可滚动，图标+短文案），不额外引入底部导航栏或汉堡菜单等第二种导航模式；Drawer 在窄屏下自动变为全屏 sheet；
- 所有交互元素提供 `aria-label`（图标按钮）、可见的 `:focus-visible` 样式、`aria-live="polite"` 承载 toast 播报；
- 颜色对比度满足 WCAG AA；状态不仅靠颜色区分，同时配图标和文字。

## 9. 与后端的兼容契约

### 9.1 保持不变

- 现有路由路径、method、CSRF 校验流程；
- `meta[name=csrf-token]`、`meta[name=ws-token]` 注入方式；
- `/ws` 升级流程和现有 WebSocket 消息结构（`refresh`/`stats`）；
- `fragment-registry` 覆盖的资源集合和 `panelRefreshMap` 的事件命名约定（参见 [AGENTS.md 面板刷新契约](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/AGENTS.md#L21-L36)）。

### 9.2 允许改变

- 任意 DOM 结构、CSS 类名、组件划分方式；
- `main.js` 全部拆分/重写；
- Hiccup 渲染函数内部实现，只要输出的 `hx-target` 锚点与后端约定一致。

### 9.3 需要同步修改的情况

若新设计需要调整某个面板的 DOM id（例如统一为 `data-panel="forwards"` 而不是裸 `id="forward-tbody"`），必须在同一次改动中完成：

1. `views.clj` 渲染函数输出新 id/属性；
2. `handlers.clj` 中对应 `hx-target`/OOB 逻辑；
3. `fragment-registry` 和 `panelRefreshMap`（如引入新的资源事件命名，需同时更新两侧文档）；
4. `main.js`/`panels.js` 中的选择器。

不允许只改一侧，避免出现“视觉已换新但刷新静默失效”的隐性 bug（这正是当前 AGENTS.md 强调的修改守则）。

## 10. 构建与静态资源管线

- 引入 Tailwind CLI（或等价的构建期 CSS 生成工具）作为**仅用于生成 `public/app.css` 的开发期工具**，产物是普通 CSS 文件，运行时不依赖任何 Node/JS 编译；不引入 Leiningen 或第二套 Clojure 构建体系，符合项目构建约束。
- `htmx` 与图标 SVG 本地化打包，不再从 `unpkg`/`cdn.jsdelivr.net` 加载，CSP `script-src`/`style-src` 可以进一步收紧为仅 `'self'`。
- 静态资源哈希从 Clojure 内置 `hash` 换成构建期 SHA-256，产出 manifest，`asset-url` 读取 manifest 而不是运行时计算。
- `daisyui.css` 替换为自建组件 CSS（体量可控），或改为按需引入的子集构建。

## 11. 实施顺序

1. **构建管线**：先把 CSS 编译移出浏览器，只做这一步就能验证“提交时错位”是否消失，风险最低、收益最大，且不改变任何视觉。
2. **Tab App Shell + 骨架屏**：铺好 Topbar + Tab Bar + 单一可见 Tab Panel 的滚动隔离容器，实现 `tabs.js` 的 URL 同步与 `hidden` 切换，加上骨架屏，不改业务视图内容。
3. **组件库抽取**：`ui/card`、`ui/badge`、`ui/form`、`ui/modal`、`ui/toast`、`ui/skeleton`、`ui/drawer`。
4. **按资源顺序替换视图**：总览 → Forward → Caddy → Docker → SmolVM → Admin，每个资源 Tab 按第 5.3 节模板把创建表单改为 Drawer，与 [docs/11-Web前端页面完全重构计划.md](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/docs/11-Web前端页面完全重构计划.md#L353) 的垂直切片顺序保持一致，两份计划共用同一迁移节奏。
5. **动效与可访问性打磨**：过渡动画、键盘导航（包含 Tab 方向键切换）、屏幕阅读器、移动端断点收尾。

## 12. 验收标准

- 首次创建/删除任意资源后不出现可感知的布局跳动（CLS 不可感知）；
- 浏览器 Network 面板不再出现 `@tailwindcss/browser` 或任何运行时 CSS 编译脚本；
- 所有面板在数据返回前展示骨架屏，不出现空白到内容的瞬间跳变；
- 组件在 Hiccup 侧可用固定 DTO 独立渲染测试，不依赖领域 atom；
- 现有后端路由、CSRF、WebSocket 协议无需改动即可跑通；除非按第 9.3 节流程同批次完成 DOM 契约迁移；
- Tab 切换不产生可感知布局跳动，支持 `?tab=` 深链接和浏览器前进/后退，Tab Bar 支持键盘方向键切换且符合 WAI-ARIA Tabs 模式；
- 键盘可达、`aria-label`/`aria-live` 覆盖关键交互，移动端断点下无横向溢出；
- `bb test-ns culvert.web-test`、`culvert.web-ws-test`、`culvert.web-server-test` 全部通过，新增浏览器级视觉回归检查覆盖“首次提交无错位”场景。

## 13. 实施进度记录

本节如实记录已完成/尚未完成的范围，避免后续误以为方案已全部落地。

### 已完成

- Tab App Shell：`render-dashboard` 改为 Topbar + `role="tablist"` Tab Bar + 6 个 `data-tab-panel` section（总览/端口转发/反向代理/容器/虚拟机/管理员），`caddy`/`smolvm`/`admin` 按现有能力探测条件渲染。
- `public/tabs.js`：Tab 切换、`?tab=` URL 同步、方向键导航、滑动高亮条、Drawer 开关与提交成功自动关闭。
- 总览 Tab：新增 `render-overview-stats-content` + `GET /panel/overview`，以可点击跳转的资源数量卡片展示，已注册进 `fragment-registry`（参与 OOB）和 120 秒轮询。
- 端口转发/反向代理/容器/虚拟机四个 Tab：创建表单均改为右侧滑入 Drawer（`#forward-drawer`/`#caddy-drawer`/`#docker-drawer`/`#smolvm-drawer`），字段、`hx-*`、CSRF 与后端完全一致；Docker 的 S3 高级选项和 SmolVM 的高级选项仍保留 `<details>` 嵌入 Drawer 内。
- 新增 `public/app.css`（`pf-` 前缀，手写静态 CSS，无浏览器运行时编译），覆盖 Topbar/Tabs/Drawer/总览统计卡片。
- 移除未使用的 `htmx-ext-ws`/`htmx-ext-sse` CDN 脚本。
- 新增对应 i18n 条目（`:tabs` 节点），新增 UI 文案仍统一从 `i18n/strings` 取值。
- 六个列表渲染函数（`render-users-tbody`/`render-admin-containers-tbody`/`render-forwards-tbody`/`render-caddy-tbody`/`render-docker-tbody`/`render-smolvm-tbody`）及其外层表格壳（`pf-table-wrap`/`pf-table pf-table--zebra`）已全部改为 `app.css` 静态样式，不再依赖 daisyUI/Tailwind 类；业务语义锚点类名（`.remark-cell`/`.credential-cell`/`.ports-cell`/`.resource-usage-cell`/`.status-cell`/`.actions-col`/`.filter-btn`/`.cred-pass-input`/`.resource-bar-cpu`/`.resource-bar-mem`）保持原名不变，避免同步修改 `main.js` 选择器。
- **14.1-b：`render-dashboard` 本体剩余 daisyUI 依赖已全部迁移**：IP Mode 提示、Caddy/Docker/SmolVM 卡片容器、不可用告警框、四个 Drawer 内的表单控件（input/select/checkbox/textarea/label）、配额栏彽章（`render-quota-bar-content`）均改用 `app.css` 新增的 `pf-card`/`pf-card-body`/`pf-card-title`/`pf-alert`/`pf-textarea`/`pf-checkbox-label`/`pf-inset-panel`/`pf-form-row`/`pf-callout-info`/`pf-footer-note` 等组件类。保留了 `.card.pf-resource-card` 中的 `.card`（仅作为 `hx-include "closest .card"`/`main.js .closest(".card")` 的选择器锚点，不提供样式）。
- **删除 `daisyui.css`/`tailwindcss.js`**：`render-dashboard` 中对二者的 `[:link ...]`/`[:script ...]` 引用、`route-dispatcher` 里对应的两条路由、`public/daisyui.css`（970KB）与 `public/tailwindcss.js`（272KB）两个文件均已删除。自此页面不再存在任何浏览器运行时 CSS 编译，第 2.1 节描述的根因彻底消除。
- **删除前补漏两处 14.1/14.1-b 未扫到的 daisyUI 依赖**（因二者都不在 `views.clj` 里，之前扫描范围没覆盖到）：
  1. `public/main.js` 的 `showToast`（toast 通知）、管理员容器过滤按钮高亮态（`btn-info`）、行隐藏（`hidden`）三处依赖 daisyUI/Tailwind 的 JS 逻辑，已改为 `pf-toast*`/`pf-btn-info`/新增的 `.hidden { display:none }` 静态规则；
  2. `src/culvert/web/handlers.clj` 里的密码修改弹碗（`handle-admin-password-form`，基于原生 `<dialog>`）和端口转发/反向代理的行内备注编辑片段（`handle-forward-remark-form`/`handle-caddy-remark-form` 等）仍用 `.modal`/`.modal-box`/`.modal-action`/`.input.input-bordered`/`.btn.btn-ghost.btn-xs` 等旧类，已改为 `pf-modal`/`pf-modal-box`/`pf-modal-actions`/`pf-input`/`pf-btn.pf-btn-ghost.pf-btn-xs`，并顺便修复了备注表单里一个预存已久的 hiccup 嵌套错误（隐藏 `<input>` 被错误写成了包含子节点的容器，void 元素不能有子节点）。
  此外新增 `dialog.pf-modal`/`.pf-modal-box`/`.pf-modal-backdrop-close` 等 CSS 实现基于原生 `<dialog>` 的弹碗样式（保留点击面板外关闭的透明覆盖层技巧）。
- **14.8（P0 安全项）WebSocket 短期 ticket 已完成**：
  1. `core.clj` 新增 `ws-ticket-store`（atom，30 秒 TTL，与 `csrf-store` 同模式）、`ws-ticket-generate`/`ws-ticket-consume`（一次性消费），并接入 `cleanup-web-security-state!` 定期清理；
  2. 新增 `POST /ws/ticket`（`handlers.clj` 的 `handle-ws-ticket`，需 `wrap-auth` + CSRF 校验），返回 `{:ticket "..."}`；
  3. `server.clj` 的 `/ws` 升级分流处改为校验 `ticket` 参数（`core/ws-ticket-consume`），消费后经 `auth/get-user` 重新取角色/配额，完全替掉旧的 `token` 参数 + 24 小时 `auth/validate-token` 方案（无需向后兼容，前后端同一次修改）；
  4. `render-dashboard` 不再预写 `meta[name=ws-token]`，不再调用 `auth/issue-token`；`main.js` 的 `connectWebSocket` 改为先 `fetch("/ws/ticket", {method:"POST", ...})` 拿到票据再开 WebSocket，重连时重新换票。
  5. 新增测试 `test/culvert/web_test.clj` 的 `ws-ticket-issue-and-consume`：无 CSRF 时 403、页面不再包含 `ws-token` 字样、票据一次性消费、不存在/空白票据均被拒绝。
- **14.9（已完成，实际方案与计划阶段提案不同）统一错误协议已完成**：`core.clj` 新增 `error-info`/`htmx-error-response`/`json-error-response`，`views.clj` 新增 `build-oob-error-response`，修复了两个实际问题（HTTP 状态码过去固定 200 不反映失败、错误完全不记服务端日志），`handlers.clj` 里全部 30 处 `.getMessage e` 直接透传已改用上述函数，同时修复了 `handle-docker-s3-test` 里残留的非 `pf-` 前缀 `.text-success`/`.text-danger`。**实际方案与 §14.9 原文描述的计划不同**：全库扫描后发现领域层的 `ex-info` 和普通 `Exception` 都是主动抛出的安全中文提示，因此不区分二者、不引入 `{:code :field}` 结构，详见 §14.9 正文。
- **14.5（已完成，范围比计划提案稍小）抽取 `src/culvert/web/ui/` 命名空间**：新增 `ui/drawer.clj`（`drawer` 函数，四个 Drawer 壳全部改用，不再各自写一份重复壳结构）、`ui/tabs.clj`（`tab-btn` 从 `views.clj` 搬过来，内容不变）。**未按原方案抽取独立的 `status-pill`/`ui.status-pill.clj`**：实际对比四个 `render-*-tbody` 的状态判断后发现只有 docker/smolvm 两处完全一致（running/stopped/其他 三态，无 tooltip），端口转发（4 支，带 tooltip）与 Caddy（2 支，无 tooltip）形式不同。根据本项目“只有至少两个真实实现的职责和语义一致时才抽取”的原则，只把真正重复的 docker/smolvm 两处抽为 `views.clj` 里的私有函数 `resource-status-badge`（不升格为 `ui/` 命名空间，因不是跨领域共用控件），端口转发/Caddy 的 `cond` 分支保持原样。
- **14.3+14.4（已完成，实际机制比计划更简单）Tab 首次惰加载与骨架屏已完成**：新增 `ui/skeleton.clj`（`table-rows`/`resource-cards`）与 `app.css` 的 `.pf-skeleton`/`.pf-skeleton-row`/`.pf-skeleton-card`（`@keyframes pf-shimmer` 水平扫光，尊重 `prefers-reduced-motion`）。除 `overview` 外的 6 个列表/卡片容器（`forward-tbody`/`caddy-tbody`/`docker-tbody`/`smolvm-tbody`/`admin-users-tbody`/`admin-containers-tbody`）首屏只输出骨架占位，带 `data-loaded="false"`，`hx-get`/`hx-trigger` 保持不变；`tabs.js` 的 `activateTab` 首次可见时调 `htmx.ajax` 拉取真实内容，成功后打 `data-loaded="true"`；`main.js` 的 WS 刷新处理对 `data-loaded="false"` 的容器跳过 `htmx.trigger`。**实际机制与 §14.3 原文计划中“标记待刷新”的描述不完全一致**：无需额外的“待刷新”状态标记，因为用户实际切换到该 Tab 时的首次拉取会直接取得服务器当前最新状态，不需要补补中间错过的事件。另外，**本轮只处理首屏延迟加载，没有完全消除后续后台轮询**：120 秒周期轮询仍会对从未访问过的 Tab 在后台持续发生（与现有行为一致，未变差，因为 hx-trigger 绑在容器元素上而非可见性）。顺带修复了一个预存的小 bug：`docker-tbody`/`smolvm-tbody` 容器 div 一直没有打 `.pf-resource-grid` 类（该类在 `app.css` 里存在但从未实际引用），导致卡片本应自适应多列的 CSS Grid 布局从未生效，本轮在修改这两个容器时顺便补上。
- **14.6（已完成）管理员 Tab 内二级 Segmented Control**：`#tab-admin` 内新增 `#admin-subtabs`（两项：用户与配额 / 主机容器分配），`app.css` 新增 `.pf-segmented`/`.pf-segmented-btn`（胶囊式小按钮组，与一级 `.pf-tabs` 完全不同样式，命中时背景高亮）。`admin-panel`/`host-containers-panel` 两个卡片各自包一层 `[:div {:data-tab-panel-sub "..."}]`，默认隐藏其中一个。`tabs.js` 新增独立的 `activateSubtab`（不写 `?tab=` URL，与一级 `activateTab` 完全独立，不注入共享 `scope` 参数）。**与 §14.6 原文提案的差异**：原方案建议给 `activateTab` 注入 `scope` 参数复用同一套切换逻辑；实际发现两套 tablist 的语义真正不同（一级写 URL 且驱动惰加载，二级不写 URL 也不涉及惰加载），强行共享一个函数反而需要引入条件分支区分两种行为，因此改为独立小函数，`data-subtab`/`data-tab-panel-sub` 属性与一级的 `data-tab`/`data-tab-panel` 完全分开命名避免混淆。
- **14.7（已完成，范围比计划提案小）次要说明文字改为 Popover**：新增 `ui/popover.clj`（`popover` 函数，“？”触发按钮 + 默认隐藏的说明块）、`app.css` 的 `.pf-popover-wrap`/`.pf-popover-trigger`/`.pf-popover-content`，`tabs.js` 新增 `data-action="popover.toggle"` 处理（点击外部/Escape 均关闭，复用已有的 Escape 监听器）。已转换端口转发 Tab 的 socat/iptables 说明与端口范围规则、Caddy Tab 的 `footer-caddy-desc`，可见行从 2-3 行段落文字压缩为 1 行短标签 + “？”按钮，完全复用现有 i18n 字符串，未新增任何条目。**未按原方案转换 `smolvm-mode-desc`/`smolvm-port-change-warning`**：实际检查后发现 `smolvm-mode-desc` 是 Drawer 创建表单内的字段提示（`pf-field-hint`），不是页面级附注文字，隐藏到点击气泡里反而降低在填写表单时的可见性；`smolvm-port-change-warning` 带 ⚠️ 警告性质，隐藏会降低用户在操作前看到警告的机会，两者都保持原样展示。
- **14.2（已完成）CSS 构建管线**：采用方案 A 的变体——不用 npm/Node，而是用 `mise` 直接引入 Tailwind CLI 的独立二进制（`mise use "github:tailwindlabs/tailwindcss@4"`，GitHub backend 安装，带 SLSA provenance 验证，完全不需要 `package.json`/`node_modules`）。新增 `styles/app.css` 作为编译源文件，只 import theme+utilities 两层（不含 preflight），故意不配置 `@source`（当前零 Tailwind utility 类用法）。`mise.toml` 新增 `[tasks.css]`（编译）与 `[tasks."css-check"]`（编译后 diff 比对，防手改 `public/app.css` 导致漂移，已接入 `[tasks.test]`）。编译产物与迁移前的手写版本已验证字符级完全等价，无任何视觉回归。详细方案对比、两次试错过程见 §14.2 正文。

### 尚未完成

无。第 14 节列举的全部事项（14.1、14.1-b、14.2、14.3、14.4、14.5、14.6、14.7、14.8、14.9）均已完成。

### 验证证据

- `bb check`、`bb fmt-check` 通过；
- `bb test`（全量 152 个测试、838 个断言）通过，0 failures / 0 errors（含新增的 `ws-ticket-issue-and-consume`、`mutation-error-uses-safe-message-and-non-200-status`、`unexpected-error-falls-back-to-generic-message` 测试）；
- 通过 `route-dispatcher` 实际触发领域错误（redef `forward/remove-forward!` 抛异常）验证：响应 HTTP 状态码为 400（不再固定 200），HX-Trigger 里的 toast 消息与异常原文完全一致；并验证空白消息时回退到通用提示。
- 14.5 完成后用 `bb` 强制四个 Drawer 均可用的条件（`cli/available?`/`cli/gpu-available?`/`s3/available?`/`smolvm-cli/available?` 都 redef 为 true，`config` 重写 `:ip-mode false`/`:smolvm-available? true`）验证：四个 Drawer 的遮盖层与面板均恰好出现一次（无因抽取造成重复或遗漏），`html.parser` 标签配对仍为 `OK`；用伪造容器数据验证 `resource-status-badge` 在 running/stopped/异常三种输入下产出正确的 `pf-badge-success`/`pf-badge-error`。
- 14.3+14.4 完成后在同样全能力条件下验证：6 个容器均正确带 `data-loaded="false"`且 `hx-get`/`hx-trigger` 与修改前完全一致（`grep` 逐个比对）；`html.parser` 标签配对仍为 `OK`；直接调用 `/panel/forwards`/`/panel/caddy`/`/panel/docker`/`/panel/smolvm` 确认这些被 `tabs.js` 惰加载调用的端点仍返回 200 且内容与容器兼容（未受影响）；`node --check` 验证 `main.js`/`tabs.js` 无语法错误。
- 14.6 完成后直接调用 `render-dashboard`（admin 角色）验证：`#admin-subtabs`/`subtabbtn-admin-users`/`subtabbtn-admin-hosts` 均正确出现，`subtab-admin-users` 无 `hidden`、`subtab-admin-hosts` 带 `hidden="hidden"`（默认隐藏符合预期），`html.parser` 标签配对为 `OK`；非 admin 角色渲染时 `#tab-admin` 内部为空（未抛异常）。
- 14.7 完成后验证两个 popover 的实际输出：触发按钮与对应 `-content` 块均存在，内容保留了原有全部 i18n 文字（未丢失信息），可见行只剩 `socat/iptables` 短标签 + “？”按钮；`html.parser` 标签配对为 `OK`。
- 通过直接调用 `render-dashboard` 并用 `html.parser` 做标签配对检查，确认新 DOM 无未闭合标签；
- 通过 `route-dispatcher` 验证 `/app.css`、`/tabs.js` 与现有静态路由都能正常返回 200；
- 分别在 `:ip-mode false`/`:smolvm-available? true` 下验证 `caddy`/`smolvm` Tab 按钮与面板正确条件渲染；
- 额外在 `cli/available?`、`cli/gpu-available?`、`s3/available?` 均为 true 时验证 Docker Drawer（包含 S3 <details> 字段）完整渲染且标签配对正常；
- `node --check` 验证 `public/main.js`、`public/tabs.js` 无语法错误；
- 通过 `route-dispatcher` 实际调用验证密码修改弹碗、端口转发/反向代理备注编辑四个片段的实际输出，确认无旧类残留且 HTML 标签配对正确；
- 未进行真实浏览器的 CLS/视觉回归测量，仅完成了 DOM 结构和服务端契约级验证。
- 六个列表函数迁移完成后，额外用 `bb` 直接调用 `render-dashboard`（真实注册用户 + 绑定 `core/*request-security-context*`）验证：admin 场景下 3 个 `<table>` 全部带 `pf-table-wrap`，且全文 `table-zebra` 残留数为 0；`bb check`/`bb fmt-check`/`bb test`（149 个测试、819 个断言）均通过。
- 14.1-b 完成并删除 `daisyui.css`/`tailwindcss.js` 后，再次全量扫描 `views.clj`：除了故意保留的 `.card.pf-resource-card`（仅作选择器锚点），`badge-`/`card-body`/`card-title`/`alert-warning`/`select-bordered`/`input-bordered`/`checkbox-`/`label-text`/`opacity-`/`text-center`/`col-span`/`grid-cols`/`rounded-lg`/`border-dashed`/`bg-base-`/`stroke-current`/`stroke-info`/`textarea-bordered` 等旧类残留均为 0。重新调用 `render-dashboard`（admin/非 admin 两种角色）确认渲染产出的 `<head>`/页尾只引用 `/app.css`、`/main.js`、`/tabs.js` 三个静态资源和 htmx CDN，不再包含 `daisyui`/`tailwindcss` 任何引用；`html.parser` 标签配对检查仍为 `OK`；`bb check`/`bb fmt-check`/`bb test`（149/819）仍全部通过。

## 14. 剩余事项解决方案

本节逐项给出第 13 节“尚未完成”每一项的具体技术方案，按价值/风险排序。本节只写方案，不预设实施范围；开始实施前先以本节为依据确认一次范围。

### 14.1（P0，最高优先级）六个列表渲染函数迁移到 app.css

这是真正消除“提交时错位”根因的最后一步。受影响的函数及其位置：

| 函数 | 位置 |
|---|---|
| `render-users-tbody` | [views.clj:90](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L90) |
| `render-admin-containers-tbody` | [views.clj:158](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L158) |
| `render-forwards-tbody` | [views.clj:246](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L246) |
| `render-caddy-tbody` | [views.clj:300](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L300) |
| `render-docker-tbody` | [views.clj:343](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L343) |
| `render-smolvm-tbody` | [views.clj:478](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/src/culvert/web/views.clj#L478) |

实际扫描这六个函数得到的 daisyUI/Tailwind 类家族与对应新组件类的映射建议如下：

| 旧类家族 | 新 `pf-` 组件类 | 说明 |
|---|---|---|
| `card` `card-body` `bg-base-300` `shadow-md` | `pf-card` | 通用卡片容器，已在 `app.css` 里有同族可复用（`pf-stat-card` 可拆出基础部分复用） |
| `badge` `badge-outline` `badge-success` `badge-error` `badge-warning` `badge-neutral` `badge-ghost` `badge-info` `badge-sm` | `pf-badge` `pf-badge-success` `pf-badge-error` `pf-badge-warning` `pf-badge-neutral` `pf-badge-ghost` `pf-badge-info` | 一个基础类加五个语义变体，映射表与本文第 4.1 节“主题基调”的语义色表保持一致 |
| `btn` `btn-ghost` `btn-xs` `btn-sm` `btn-square` `btn-outline` `btn-error` `btn-success` `btn-info` | `pf-btn` `pf-btn-ghost` `pf-btn-xs` `pf-btn-sm` `pf-btn-square` `pf-btn-danger` `pf-btn-success` `pf-btn-info` | 现有 `pf-btn`/`pf-btn-primary`/`pf-btn-block` 已存在，只需补尺寸和色彩变体 |
| `input` `input-bordered` `input-xs` `select` `select-bordered` `select-xs` `checkbox` | `pf-input` `pf-input-xs` `pf-select` `pf-checkbox` | 与 Drawer 内已经留存的 `.input.input-bordered` 过渡共存片段无关，最终一步会一并改掉 |
| `table` `table-zebra` `table-xs` | `pf-table` | 新增斑马纹、密度变体通过 `pf-table--zebra`，不引入 `-xs` 类型前缀命名，改用固定尺寸 |
| `progress` `progress-info` `progress-secondary` | `pf-meter` `pf-meter-cpu` `pf-meter-mem` | 用 CSS `<progress>` 或 `<div>`+`background` 实现，固定高度避免进度条类型切换时高度变化 |
| `remark-cell` `credential-cell` `ports-cell` `resource-usage-cell` `status-cell` `actions-col` | 保留原名，改为 `pf-` 前缀 | 这些是业务语义锚点（JS 靠 `.resource-usage-cell` 等选择器定位，参见 [main.js updateResourceBars](worktree://458c3578-7ace-4ab0-b899-3f036c8e72c1/public/main.js#L343)），改名时必须同步改 `main.js` 中的选择器，否则 stats 推送会静默失效 |
| 其余布局 utility（`flex` `grid-cols-*` `gap-*` `w-*` `text-*` `opacity-*` 等） | 直接改用 `app.css` 中已有的 `--pf-space-*` 变量写组件级 CSS，不再以 utility 组合方式堆叠 | 避免重建一套 Tailwind |

迁移顺序建议按复杂度递增，方便早期写法错误尽早暴露：

1. `render-forwards-tbody`（纯表格，无卡片）
2. `render-caddy-tbody`（结构与 1 几乎一致）
3. `render-users-tbody`、`render-admin-containers-tbody`（表格 + 行内表单控件）
4. `render-docker-tbody`、`render-smolvm-tbody`（卡片网格 + 进度条 + 凭据单元，两个几乎重复，完成第 1 个后应该抽出共享的 `resource-card` 辅助函数再写第二个，避免重复两遍）

每个函数迁移完成后的验收方式：

- 新增契约测试：断言该函数返回的 HTML 不再包含一个固定的“旧类黑名单”（`badge-` `btn-` `input-bordered` `table-zebra` 等前缀），可写一个共享断言函数在 `web_test.clj` 里复用；
- 现有 `bb test-ns culvert.web-test` 必须仍然全部通过（它不断言具体 CSS 类，只断言字段名/CSRF/状态码，因此不会因类名改变而失败，但仍得回归）；
- 回归过 `render-dashboard`（参见第 13 节验证证据中的 `html.parser` 方法）确认无未闭合标签。

六个函数（含其外层表格壳）与 14.1-b（`render-dashboard` 本体剩余 daisyUI 依赖）均已完成迁移，并已执行以下四步删除操作：

1. 已删除 `render-dashboard` 中对 `daisyui.css`/`tailwindcss.js` 的 `[:link ...]`/`[:script ...]` 引用；
2. 已删除 `route-dispatcher` 里对应的两条静态路由；
3. 已删除 `public/daisyui.css`、`public/tailwindcss.js` 两个文件（共计 970KB + 272KB）；
4. 已更新 `AGENTS.md` 静态资源表（三条固定路由：`/main.js`、`/app.css`、`/tabs.js`）和本文第 13 节。

### 14.1-b（已完成）`render-dashboard` 本体剩余 daisyUI 依赖迁移

14.1 只覆盖了六个列表渲染函数及其表格壳，未覆盖 `render-dashboard` 本体直接书写的其余部分。实测残留（迁移前扫描结果，供后续类似排查参考）：

| 旧类家族 | 出现位置 | 数量 | 迁移结果 |
|---|---|---|---|
| `card` `card-body` `card-title` `bg-base-200` `shadow-xl` | Caddy/Docker/SmolVM 卡片容器、IP Mode 提示卡片、管理员两个面板 | 共 17 处 | 改为 `pf-card`/`pf-card-body`/`pf-card-title`，`border-l-error`/`border-l-info` 改为 `pf-card--accent-error`/`pf-card--accent-info` 修饰符 |
| `alert` `alert-warning` | 不可用提示框（Caddy 未安装、Docker/SmolVM 不可用）、IP Mode 横幅 | 共 7 处 | 改为 `pf-alert`（警告色）/`pf-alert.pf-alert--info`（IP Mode 横幅），SVG 图标改用显式 `:stroke "currentColor"` 属性替代 `stroke-current`/`stroke-info` 类 |
| `input` `input-bordered` `select` `select-bordered` `label` `label-text` `checkbox` `textarea` `textarea-bordered` | 四个 Drawer 内的创建表单控件、管理员创建用户表单 | `input-bordered` 18 处、`select-bordered` 6 处、`label-text` 26 处 | 改为 `pf-input`/`pf-select`/`pf-checkbox`/`pf-textarea`（新增）/`pf-label-text`；外层 `<label>` 无需额外类（`.pf-field` 已是 flex column） |
| `badge` 系列 | 用户角色徽章、配额栏彽章 | 30 处（多数已在六个列表函数内迁移，剩余在配额栏 `render-quota-bar-content` 和用户角色徽章） | 改为 `pf-badge`/`pf-badge-outline`，配额数字改用语义化 `<strong>` 替代 `.font-bold` |
| `grid-cols-12`/`col-span-*`/`flex`/`items-*` 等 Tailwind 布局 utility | 管理员创建用户表单、S3/高级选项面板、复选框行 | 约 20+ 处零散使用 | 改为新增布局组件类 `pf-form-row`/`pf-form-actions`/`pf-checkbox-label`/`pf-inset-panel`，不重建通用 utility 系统 |
| `text-center`/`opacity-50`/`mt-*`/`mb-*` 等零散文本/间距 utility | 各 Tab 底部说明文字、卡片描述文字 | 约 15 处 | 改为 `pf-footer-note`/`pf-footer-note--tight`/`pf-callout-info`，复用既有 `pf-text-muted` |

`modal`/`toggle`/`stats`/`stat-` 经复核均为 id/hx-post 路径中的字面子串（如 `/toggle`、`password-modal-container`），并非 daisyUI CSS 类，无需迁移。

迁移完成后用 `bb` 直接调用 `render-dashboard`（admin/非 admin 两种角色）验证：`html.parser` 标签配对检查为 `OK`；渲染产出的 `<head>`/页尾只引用 `/app.css`、`/main.js`、`/tabs.js` 三个静态资源和 htmx CDN，不再包含 `daisyui`/`tailwindcss` 字符串；全文残留旧类扫描（`badge-`/`card-body`/`input-bordered`/`select-bordered`/`opacity-`/`col-span` 等）计数为 0（仅 `.card.pf-resource-card` 中的 `.card` 按设计保留，用作 `hx-include`/`main.js` 选择器锚点，不提供样式）；`bb check`/`bb fmt-check`/`bb test`（149/819）全部通过。

### 14.2（已完成）CSS 构建管线

**决策结果**：采用方案 A 的变体——不用 npm/Node，而是用 `mise` 直接引入 Tailwind CLI 的**独立二进制**（`mise use "github:tailwindlabs/tailwindcss@4"`，mise 的 GitHub backend，带 SLSA provenance 验证）。这个变体完全不需要 `package.json`/`package-lock.json`/`node_modules`，与项目现有 `deps.edn + tools.build` 的工具链约束完全兼容，因此不存在原方案 A 列出的代价（引入 Node 工具链），保留了方案 A 的优点（可用 utility 类，工具保证一致性）。

**具体实施**：

1. `mise.toml` 新增 `[tools]` 中的 `"github:tailwindlabs/tailwindcss" = "4"`（实际安装版本 4.3.3）；新增 `[tasks.css]`（`tailwindcss -i styles/app.css -o public/app.css`）与 `[tasks."css-check"]`（编译后 `git diff --exit-code public/app.css`，不一致则报错退出），并把 `css-check` 接入 `[tasks.test]` 的执行链，防止有人手改 `public/app.css` 却没同步改 `styles/app.css` 而不被发现。
2. 新增源文件 `styles/app.css`，语法上只 `@import` theme + utilities 两层，**故意不导入 preflight**（Tailwind v4 的 `@import "tailwindcss";` 简写会带入 Preflight 全局 CSS reset，会打乱 14.1~14.9 已完成的手写 `pf-` 视觉体系）：
   ```css
   @layer theme, base, components, utilities;
   @import "tailwindcss/theme.css" layer(theme) source(none);
   @import "tailwindcss/utilities.css" layer(utilities) source(none);
   ```
3. 加 `source(none)` 且**暂不添加任何 `@source` 路径**。因为项目当前零 Tailwind utility 类实际用法（14.1~14.9 已把所有旧的 daisyUI/Tailwind 类全部清除，全部改为手写 `pf-` 前缀类），没有真实需求去扫描任何文件。这样编译输出 = 纯粹的手写 CSS 内容 + 空的 theme/utility 层。待真正开始用 Tailwind utility 类时，再精确添加 `@source` 到具体文件或小目录，不要指向整个 `src` 目录。
4. 新增 `public/app.css` 现在是 `styles/app.css` 的编译产物，不再手改；`src/culvert/web/AGENTS.md` 已同步记录这个约定。

**试错过程**（均已修复，记录供后续参考）：

1. 最初未加 `source(none)`，Tailwind 默认从当前目录自动扫描所有纯文本文件（包括 CSS 源文件自身），把 CSS 属性值里的英文单词（如 `position: fixed` 里的 "fixed"）误判为候选 utility 类名，生成了大量不需要的 `.visible`/`.fixed`/`.static`/`.sticky`/`.container` 等类。
2. 加了 `source(none)` 并显式 `@source "../src/**/*.clj"` 后，CSS 自身不再被扫描，但仍然从 `.clj`/`.js` 源码文件的注释/字符串/标识符里误命中同名无参数 Tailwind utility（如 `table`、`hidden`、`filter`、`transform`、`resize`、`transition`）。这是 Tailwind 纯文本扫描器的已知局限：只要源码里出现这些常见英文单词就会误命中。因此最终方案改为步骤 3 描述的“完全不加 `@source`”。
3. 最初在注释里写了 `src/**/*.clj` 这种 glob 语法作为示例，但 `**/` 这个子串本身包含 `*/`，会提前闭合 CSS 块注释，导致注释后半段泄漏成非法的字面 CSS 文本，出现在 `:root {` 前面。已修复：把注释里的具体 glob 语法去掉，改成纯文字描述，并在注释末尾加了自我提醒避免重踏。

**等价性验证**：用 `mise exec -- tailwindcss -i styles/app.css -o /tmp/xxx.css` 先在临时路径编译，用 Python 脚本剥离两侧注释、折叠空白、去掉 Tailwind 自动插入的 `@layer` 声明行后逐字符比较，与当时的手写 `public/app.css`完全相等（两侧标准化后长度均为 17439 字符），证明迁移零风险——编译产物不会改变任何视觉效果。正式生成 `public/app.css` 后也用同样方法复验一遍，同样等价；全量 `bb check`/`bb fmt-check`/`bb test`（152 tests / 838 assertions）无回归。

### 14.3+14.4（已完成）Tab 首次惰加载与骨架屏组件

实际实施如下（与本节原文相比，机制更简单，不需要额外的“待刷新”状态机，详见本节末尾说明）：

1. **骨架屏组件**：`app.css` 新增 `.pf-skeleton`（背景渐变 + `@keyframes pf-shimmer` 水平扫光，`prefers-reduced-motion` 时关闭动画仅保留静态背景）、`.pf-skeleton-row`（表格行）、`.pf-skeleton-card`（卡片，固定高度与真实卡片同量级）。新增 `src/culvert/web/ui/skeleton.clj`：`table-rows [colspan n]`（每行一个跨列闪光条）、`resource-cards [n]`（n 张卡片形状的占位），固定 n=3（无“上次已知数量”可参考，因为这是首屏的第一次渲染）。
2. **服务端**：除 `overview` 外的 6 个列表/卡片容器（`forward-tbody`/`caddy-tbody`/`docker-tbody`/`smolvm-tbody`/`admin-users-tbody`/`admin-containers-tbody`）首屏不再调用 `render-*-tbody`，改为输出 `ui.skeleton/table-rows`（表格类，colspan 按原有 empty-state 逻辑取：`forward` 9/8、`caddy` 8/7、`admin-users` 10、`admin-containers` 6）或 `ui.skeleton/resource-cards`（docker/smolvm 卡片类），并打上 `data-loaded="false"`。`hx-get`/`hx-trigger` 完全保留不变。
3. **客户端**：`tabs.js` 新增 `lazyLoadPanel(panel)`，在 `activateTab` 把面板变为可见后调用：对面板内所有 `[data-loaded="false"]` 容器，直接读它自己的 `hx-get` 属性值作为 endpoint（不另引入 `data-lazy-endpoint`，避免两处重复维护同一个 URL），调 `htmx.ajax("GET", endpoint, {target: container, swap: "innerHTML"})`，`.then()` 成功后打上 `data-loaded="true"`（触发前先打 `"pending"` 防重入）。直接存在 `?tab=` 深链接时也能正确触发，因为都经过同一个 `activateTab` 入口。
4. **WebSocket 刷新联动**：`main.js` 的 WS `refresh` 处理循环里，调 `htmx.trigger` 前先检查 `el.dataset.loaded !== "false"`，若仍为 `"false"`（从未访问过）则跳过。**不需要额外的“待刷新”标记机制**：因为用户真正切换到该 Tab 时的首次拉取会直接拿到服务器当前最新状态，不会因为错过中间某次 WS 事件而丢失数据。
5. **阶段性限制**：120 秒周期轮询仍会对从未访问过的 Tab 在后台持续发生（因为 `hx-trigger` 绑在容器元素上而非可见性，与修改前行为一致，未变差）。本轮只解决“首屏”的延迟加载，若需进一步消除长时间会话中未访问 Tab 的后台轮询，需额外处理 hx-trigger 的条件门控，未列入本轮范围。
6. **顺带修复的 bug**：`docker-tbody`/`smolvm-tbody` 容器 div 一直没有打 `.pf-resource-grid` 类（该类在 `app.css` 里存在但从未实际引用），导致卡片本应自适应多列的 CSS Grid 布局从未生效，本轮在修改这两个容器时顺便补上。

### 14.5（已完成，抽取范围比计划提案稍小）抽取 `src/culvert/web/ui/` 命名空间

四个 Drawer（forward/caddy/docker/smolvm）结构完全一致，只有 id、标题、表单字段不同，是最容易抽取的部分，实际照下方代码实施：

```clojure
(defn drawer [{:keys [id title body]}]
  (list
   [:div.pf-drawer-backdrop {:id (str id "-backdrop") :hidden true :data-action "drawer.close" :data-drawer id}]
   [:aside.pf-drawer {:id id :hidden true :role "dialog" :aria-modal "true" :aria-labelledby (str id "-title")}
    [:div.pf-drawer-header
     [:h3 {:id (str id "-title")} title]
     [:button.pf-drawer-close {:type "button" :data-action "drawer.close" :data-drawer id} "×"]]
    body]))
```

已新增 `src/culvert/web/ui/drawer.clj`（仅含上述函数），四个创建表单处已改为 `(ui.drawer/drawer {:id "forward-drawer" :title (:add-forward btn) :body [:form.pf-drawer-body ...]})`。同批已抽出 `tab-btn` 到 `src/culvert/web/ui/tabs.clj`（从 `views.clj` 搬过去，未改变行为）。

**`status-pill` 未按原提案抽到 `ui/`**：实际逐个比对四个 `render-*-tbody` 的状态判断 `cond` 后发现，只有 docker 与 smolvm 的状态彽章逻辑完全一样（running/stopped/其他 三态，同样的 `pf-badge-success`/`pf-badge-error`，无 tooltip），而端口转发是四分支带 `:title` tooltip、Caddy 是二分支无 tooltip，与前两个形态不同。本项目 `AGENTS.md` 的 “Simple Made Easy” 约定“只有至少两个真实实现的职责和语义一致时才抽取公共机制”，因此只将真正重复的 docker/smolvm 两处抽为 `views.clj` 内部的私有函数 `resource-status-badge`，不升格为独立的 `ui/status-pill.clj` 命名空间（不是跨领域共用控件，只是本文件内的调用点去重）；端口转发/Caddy 的 `cond` 保持各自独立，强行归并反而会引入错误分支。

### 14.6（已完成）管理员 Tab 内二级 Segmented Control

在 `#tab-admin` 内新增了一个作用域限定的 `#admin-subtabs`（`role="tablist"`，两项：用户与配额 / 主机容器分配），`admin-panel`/`host-containers-panel` 两个卡片各自包一层 `[:div {:data-tab-panel-sub "..."}]`，默认隐藏其中一个。`tabs.js` 新增独立的 `activateSubtab` 函数，不写入 URL。**实际实施与本节原提案的差异**：原方案建议给现有 `activateTab` 注入 `scope` 参数复用，但实际实施时发现两套 tablist 的行为真正不同（一级写 `?tab=` URL 且驱动 14.3 的惰加载，二级既不写 URL 也不涉及惰加载），强行共享同一函数反而需要在函数内部引入条件分支区分两种行为，不如写成两个独立小函数清晰。`data-subtab`/`data-tab-panel-sub` 属性与一级的 `data-tab`/`data-tab-panel` 完全分开命名，避免两套机制互相干扰。

### 14.7（已完成，范围比本节原提案小）次要说明文字改为 Popover

新增 `ui/popover.clj`：一个小型“？”图标按钮 + `role="tooltip"` 内容块，默认 `hidden`，点击切换，通过 `aria-describedby` 关联。交互逻辑复用 `tabs.js` 已有的 Escape 监听器，新增“点击外部关闭”处理。实际转换了以下位置：

- 端口转发 Tab 的 socat/iptables 说明、端口范围规则（`footer-socat`/`footer-range-help` 相关段落）；
- Caddy Tab 的 `footer-caddy-desc`。

两处均完全复用现有 i18n 字符串，未新增条目，只是把原本总是可见的解释性文字收进默认隐藏的气泡，可见行只保留短标签（如 `socat`/`iptables`）+ “？”按钮。

**未按本节原提案转换 `smolvm-mode-desc`/`smolvm-port-change-warning`**：实际检查后发现两者与前两个没有可比性：

- `smolvm-mode-desc` 是 SmolVM 创建 Drawer 表单内的字段提示（`pf-field-hint`），不是页面级附注文字。Drawer 只在创建时打开，不占用常驻屏幕空间，把它藏到需要额外点击的气泡里反而降低在填写表单当下的可见性，与 14.7 的目标（减少常驻页面的次要文字占用）不符；
- `smolvm-port-change-warning` 带 ⚠️ 警告性质，描述的是操作前应该知道的副作用（端口变更需要短暂停机），隐藏到默认不可见的气泡里会降低用户在误操作前看到警告的机会。

两者都保持原样展示，不强行按原方案列表执行。

### 14.8（已完成）WebSocket 短期 ticket

旧方案：`render-dashboard` 把 24 小时有效期的 `auth/issue-token` 结果写入 `meta[name=ws-token]`，前端拼接到 `/ws?token=` 上。实际实施如下：

1. `core.clj` 新增 `ws-ticket-store`（atom，30 秒 TTL，仲 `csrf-store` 模式）、`ws-ticket-generate`/`ws-ticket-consume`（一次性消费，消费时立即从 store 中删除），接入 `cleanup-web-security-state!` 定期清理过期条目；
2. 新增 `POST /ws/ticket`（`handlers.clj` 的 `handle-ws-ticket`，需 `core/wrap-auth` + CSRF 校验），返回 `{:ticket "..."}`；
3. `server.clj` 的 `/ws` 升级分流处改为校验 `ticket` 参数（`core/ws-ticket-consume`），成功后经 `auth/get-user` 重新取角色/配额（而不是信任票据里的快照），完全替掉旧的 `token` 参数 + `auth/validate-token` 方案（前后端同一次修改，无需过渡期兼容）；
4. `render-dashboard` 不再预写 `meta[name=ws-token]`，不再调用 `auth/issue-token`；`main.js` 的 `connectWebSocket` 改为先 `fetch("/ws/ticket", {method:"POST", ...})` 拿到 ticket 再开 WebSocket，重连时重新换票；
5. 新增 `test/culvert/web_test.clj` 的 `ws-ticket-issue-and-consume` 测试：无 CSRF 时 403、页面不再包含 `ws-token` 字样、发票后一次性消费、不存在/空白票据均被拒绝。

### 14.9（已完成，实际方案与计划阶段提案有意识地不同）统一错误协议

**计划阶段的假设错了**：原方案假定“`ex-info` 安全可信，普通 `Exception` 可能泄露内部信息”。实际全库扫描 `auth.clj`/`caddy.clj`/`docker.clj`/`forward.clj`/`smolvm/manager.clj` 后发现：领域层绝大部分验证/业务错误用的是 `(throw (Exception. 安全提示))`（数十处，如“用户名已存在”“端口未被转发”），而不是 `ex-info`；`ex-info`（带 `:type`）反而主要用于内部工作流/补偿失败场景。两类异常的消息都是领域层主动为用户书写的中文提示，没有任何内部路径/堆栈/参数泄露。若按原方案注定“只信 `ex-info`”，会把大量现有有用、安全的具体错误提示（如“用户名已存在”）退化成无用的通用提示，是一个真实回归，而不是安全修复。

因此实际实施时改为保守、与代码库真实约定匹配的方案，只修复两个已确认的实际问题，不引入 `{:code :field}` 结构（目前没有任何消费方解析这些字段，强行引入属于不减少交织的无用抽象）：

1. 旧问题 A：`views/build-oob-response`/`core/htmx-response` 无论成功失败都固定返回 HTTP 200，不反映失败；
2. 旧问题 B：错误完全不记服务端日志，零可观测性。

方案：

1. `core.clj` 新增 `error-info [e]` → `{:message ... :status 400}`：直接透传 `(.getMessage e)`（与领域层的安全提示约定保持一致），消息为空/空白时回退到新增的 `:operation-failed` i18n 通用提示；无论否都用 `log/warn` 记一条服务端日志；
2. 新增 `core/htmx-error-response`（OOB 外的 HTMX 错误分支）、`views/build-oob-error-response`（OOB 错误分支）、`core/json-error-response`（JSON 接口错误分支，`{:error "..."}` 字符串形式与 `main.js` 现有消费契约保持一致），三处均使用 `error-info` 推导的状态码，不再固定 200；
3. `handlers.clj` 里全部 30 处 `(catch Exception e ... (.getMessage e) "error")` 改用上述三个新函数（按原来的调用形式分为 `build-oob-response`/`htmx-response`/`json-response` 三类），顺便修复了 `handle-docker-s3-test` 里残留的非 14.1 范围的 `.text-success`/`.text-danger`（无 `pf-` 前缀，daisyUI 删除后已失效）；
4. 新增 `test/culvert/web_test.clj` 的 `mutation-error-uses-safe-message-and-non-200-status`（验证领域错误保持原样透传且状态码为 400）与 `unexpected-error-falls-back-to-generic-message`（验证空白消息回退、`ex-info`/`Exception` 两种异常类型消息均直接透传）。

未改动的部分（故意）：不区分 `ExceptionInfo` 与普通 `Exception`，二者同样处理，因为它们在本项目领域层都是“主动抛出的安全提示”，按异常类型区对待反而会引入错误的分支。

### 14.10 执行顺序与优先级汇总

| 优先级 | 事项 | 风险 | 是否需开发者确认 | 状态 |
|---|---|---|---|---|
| P0 | 14.1 六个列表函数迁移 | 中（改动面广，但有契约测试和 HTML 标签配对检查兼底） | 否 | ✅ 已完成 |
| P0 | 14.1-b `render-dashboard` 本体剩余迁移 + 删除 daisyUI/Tailwind 依赖 | 中 | 否 | ✅ 已完成 |
| P0 | 14.8 WebSocket 短期 ticket | 低 | 否 | ✅ 已完成 |
| P1 | 14.3 + 14.4 Tab 惰加载与骨架屏 | 中（改变首屏数据流，需回归测试） | 否 | ✅ 已完成 |
| P1 | 14.5 抽取 `ui/` 命名空间 | 低（纯重命名） | 否 | ✅ 已完成 |
| P1 | 14.9 统一错误协议 | 中 | 否 | ✅ 已完成 |
| P2 | 14.6 管理员二级 Segmented Control | 低 | 否 | ✅ 已完成 |
| P2 | 14.7 说明文字改 Popover | 低 | 否 | ✅ 已完成 |
| P2 | 14.2 引入 Tailwind CLI（mise 独立二进制） | 低（编译产物与手写版语义已验证完全等价） | 否 | ✅ 已完成 |

已按建议顺序完成 14.1、14.1-b、14.8、14.9、14.5、14.3/14.4、14.6/14.7 与 14.2。至此第 14 节列举的全部事项均已完成。总结：“首次提交不再触发样式注入延迟”这个最初报告的问题的根因（浏览器运行时 CSS 编译）已彻底消除；WebSocket 不再依赖预写在页面里的 24 小时 Bearer token；领域错误的 HTTP 状态码不再固定为 200，且均有服务端日志；Drawer 壳、Tab 按钮、骨架屏、Popover 均成为无领域依赖的可复用 `ui/` 组件；非默认 Tab 首屏不再预先渲染列表内容，改为骨架屏 + 首次可见时惰加载；管理员 Tab 内部有了二级导航；次要说明文字收进了默认隐藏的 Popover；CSS 从手写改为由 Tailwind CLI（mise 引入的独立二进制）从 `styles/app.css` 编译生成 `public/app.css`，未引入任何 Node 工具链。
