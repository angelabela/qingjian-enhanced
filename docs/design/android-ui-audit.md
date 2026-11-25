# Impeccable 原生 Android 审查报告

- 审查基线：781 号工作流，提交 6d5ee1084cba4d7bd0493d7ff4e6df1e1d56aa94
- 审查分支：design/impeccable-native-audit-781
- Impeccable 上游固定提交：b49177d32788f2dc585c42831d845dacf1ebed2c
- 范围：Android 输入法键盘、候选栏、工具栏、功能面板、设置 App。
- 方法：按 Impeccable native Android、audit.native、critique rubric 与 craft-floor 对源码审查。该项目使用 Android Views / Canvas；网页 detector 与浏览器 live mode 不适用于这些原生控件。
- 限制：本次没有连接用户手机或 Android 模拟器，未取得新版本屏幕截图、TalkBack 录屏、帧率/内存剖析；凡依赖设备表现的结论均标为待验。
- 执行边界：本次通过 GitHub 源码访问完成原生代码审查，没有本地仓库 checkout，未运行 Impeccable launcher/engine 命令；本报告是按固定版本 Skill 文档执行的人工源码审查，不冒充 CLI 自动扫描结果。

## Audit Health Score（781 基线源码）

| # | 维度 | 分数 | 主要发现 |
|---|---|---:|---|
| 1 | Accessibility | 1/4 | KeyboardView、CandidateBarView 与 PanelView 为自绘 View，源码未见虚拟无障碍子节点；Canvas 控件不能仅凭可见图形被 TalkBack 正确操作。 |
| 2 | Performance | 2/4 | 云层纹理异步生成、视频 URI 不变时复用 TextureView 是优点；生词本最多可显示 500 条，当前使用普通 View 列表而非回收列表，且缺少本轮设备性能剖析。 |
| 3 | Appearance & Theming | 3/4 | Rust Palette 与 AppColors 形成统一主题来源，并有次级文字对比度回退；少数效果色仍在 Android 层独立配置，属于有意自定义但需持续核对。 |
| 4 | Platform Conformance | 2/4 | Activity、系统 Insets、原生 AlertDialog 与系统字体均有使用；部分设置分段和输入法面板采用自绘交互，需补齐原生触摸、焦点与 TalkBack 语义。 |
| 5 | Adaptivity | 2/4 | 设置页采用单列滚动、主题预览两列；源码未见明确 WindowSizeClass / 大屏布局策略，横屏、分屏与折叠屏需设备验证。 |
| **总分** | | **10/20** | **Acceptable：存在需优先处理的可访问性与适配问题。** |

以上为源码审查分数，不是实机测量结果。后续需在真实设备完成复审后再调整分数。

## Platform Conformance Verdict
青简不是网页移植。它使用原生 Android Activity、View、ScrollView、AlertDialog、WindowInsets 与 InputMethodService；自绘键盘和 Canvas 面板是输入法的核心交互形态，不能简单替换成标准表单控件。

主要偏差不是“视觉不够 Material”，而是自绘控件的可访问性、触摸目标和系统字体缩放语义不足。应保留输入法特有的键盘与视觉定制能力，同时补齐 Android 用户熟悉的反馈、焦点、Back 与辅助技术行为。

## Detailed Findings

### P1 — 自绘键盘与面板缺少可访问的子控件语义
- 位置：keyboard/KeyboardView.kt、candidates/CandidateBarView.kt、panel/base/PanelView.kt
- 类别：Accessibility / Platform Conformance
- 影响：这些 View 通过 Canvas 绘制大量按键、候选和面板选项，触摸由自定义 onTouchEvent 分发；本次检查未发现 ExploreByTouchHelper、AccessibilityNodeProvider 或等价虚拟节点实现。仅给父 View 一个 contentDescription 不能让 TalkBack 用户逐个访问与操作选项。
- 建议：分模块建立虚拟节点树，先从键盘按键、候选项和面板表头/主操作开始；节点需提供 bounds、label、click/long-click/scroll action 与选中状态。不可一次性改写所有触摸判定。
- 当前分支进展：KeyboardView 已加入 AccessibilityNodeProvider，为每个可见按键提供独立文本、边界、聚焦/点击/悬停事件；CandidateBarView 已为候选词提供独立节点、选中状态与辅助滚动，并补齐超出 Rust 位图的补充候选区域。普通点按仍调用原有 listener，不模拟长按或方向手势。
- 未完成：PanelView 各面板表头、标签、滑块和自绘选项尚未建立完整虚拟节点树；键盘侧边拼音列与工具栏子项也需进一步补齐。
- 状态：键盘主键与候选词源码已实现，待 CI 编译和真机 TalkBack/触摸探索验收。

### P1 — Canvas 面板文字未跟随系统字体缩放
- 位置：panel/base/Ink.kt、panel/base/PanelView.kt
- 类别：Accessibility
- 影响：Ink 原先以 sp * density 设置 Paint.textSize，实际按 dp 缩放，系统 fontScale 改变时自绘面板文字不会放大。
- 本轮修改：Ink 增加 scaledDensity，PanelView 将 displayMetrics.scaledDensity 传入；Canvas 面板的文字与测量宽度按系统字体缩放。
- 待验：Android 设置字体大小 1.0、1.3、最大档，检查长标题、四列效果名称、滑块值与剪贴板长文本是否裁切。

### P2 — 分段选择与生词筛选的视觉控件小于推荐触摸目标
- 位置：settings/widget/SettingsWidgets.kt、settings/home/VocabularyPage.kt、settings/widget/TabBar.kt
- 类别：Accessibility / Adaptivity
- 影响：原分段选项视觉与点击区域约 28dp；生词筛选胶囊高度也较小，单手点击容易误触。
- 本轮修改：保留原有紧凑胶囊外观，将分段选项、生词筛选与底部 Tab 的命中区域扩展到至少 48dp；为选中项补充 selected / contentDescription 语义。
- 待验：单手拇指操作、TalkBack 焦点顺序与屏幕放大下检查。

### P2 — 生词本长列表缺少 View 回收
- 位置：settings/home/VocabularyPage.kt
- 类别：Performance
- 影响：生词本数据上限为 500 条，当前页面按普通 View 创建条目；低端设备上大量条目可能增加初次布局和内存压力。
- 建议：单独评估使用 RecyclerView/ListAdapter 或分页；需保留搜索、等级筛选、标认识、删除与当前滚动位置，不能只为虚拟化破坏交互。
- 状态：未在本轮修改，待性能测量后安排。

### P2 — 大屏与横屏适配策略不完整
- 位置：settings/home/MainActivity.kt、settings/page/PageActivity.kt、settings/page/AppearanceActivity.kt
- 类别：Adaptivity
- 影响：当前主要是单列页面与两列主题卡片，未见明确的大屏导航栏/侧边栏策略。
- 建议：先确定平板、横屏与分屏是否属于正式支持范围，再采用窗口宽度分级；不应仅把手机布局拉宽。
- 状态：待设备/窗口模式验证。

### P3 — 动态效果与系统减少动画偏好需要核对
- 位置：service/CloudAtmosphereRenderer.kt、service/DynamicVideoBackgroundView.kt、keyboard/paint/KeyPainter.kt 及相关动画面板
- 类别：Accessibility / Performance
- 影响：多种动态背景、跑马灯与面板动画是产品特色；本轮源码抽查未确认所有效果是否遵循系统动画缩放设置或在不可见时停止。
- 建议：为非必要循环动画提供系统减少动画路径，保留按键按下等必要反馈；按实际显示生命周期停止昂贵渲染。
- 状态：待设备检查，不擅自关闭用户已选择的特效。

## Positive Findings
- Rust Palette 是主题颜色来源，AppColors 派生次级颜色并检查对比度，避免设置页另造完全独立的主题体系。
- PageActivity 与 MainActivity 处理系统栏和 IME Insets；滚动区域独立，减少输入法弹出时整页重建。
- CloudAtmosphereRenderer 将纹理生成放到异步任务并缓存平铺纹理；动态效果有分层绘制。
- DynamicVideoBackgroundView 在 URI 不变时更新透明度/模糊，不重建 TextureView/Surface，避免不必要的视频闪断。
- 外观设置提供真实候选条与键盘预览，用户能在应用设置页直接比较主题。
- 用户可配置的主题、键盘皮肤、工具栏、手势和按键效果是产品优势，应作为设计资产保留，而非被“统一风格”抹平。

## Critique Notes
- 设计特异性：高。键盘候选、输入手势、语言学习注释、用户自定义背景构成明确的输入法体验，不应套用通用 SaaS 面板模板。
- 认知负荷：中等偏高。动态背景样式与按键效果有多项并列选择；分组与选中反馈有帮助，但后续可通过类别分组和预览降低比较成本，不删除现有选项。
- 情绪路径：高频输入阶段应安静、即时；进入自定义面板时可以有表达力；保存或切换后应明确反馈且不打断输入。
- 人物视角：熟练用户的手势与定制选项是优势；新用户需要清楚的标题、当前值和反馈；TalkBack 用户会被自绘控件语义不足阻断；单手用户需要更大的筛选命中区。
- Nielsen 启发式（源码初评，0–4）：系统状态可见 3；现实语言 3；用户控制与自由 4；一致性 3；错误预防 3；识别优于记忆 3；灵活高效 4；美学与简约 3；错误识别与恢复 2；帮助与文档 3。此为代码审查估分，非用户测试结果。

## Recommended Actions
1. [P1] impeccable harden：为键盘、候选栏与面板建立虚拟无障碍节点；分模块实施并逐项回归手势。
2. [P1] impeccable adapt：在大字体与窗口变化下复核自绘面板、主题卡片与筛选项的布局。
3. [P2] impeccable optimize：对生词本 500 条列表进行低端设备测量，再决定是否引入回收列表。
4. [P2] impeccable animate：检查云层、视频、跑马灯和面板动画的生命周期与系统减少动画路径。
5. [P2] impeccable polish：完成各主题、深浅色、字体缩放和主要面板的最终视觉回归。

## Verification Status
- 已完成：源码审查、设计系统文档、首轮触摸区域与字体缩放源码调整；784 号固定签名 Release 构建、签名验证和产物上传成功。
- 当前分支已实现：键盘主按键与候选词的虚拟无障碍节点、候选栏辅助滚动。
- 待 CI：当前 0.3.60-dev 版本的 Android Release 构建、签名验证与 APK 产物检查。
- 待真机：TalkBack 逐键/逐候选访问、焦点顺序、键盘侧边列与功能面板语义、字体缩放、横竖屏/分屏、主题切换、动态背景性能与所有既有自定义项回归。
