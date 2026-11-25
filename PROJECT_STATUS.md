## 2026-11-25：Impeccable 审查与第二阶段无障碍优化

- 已安装测试的基线：781 号工作流，APP 版本 0.3.58-dev / versionCode 358。
- 首轮候选版本：784 号工作流构建成功，APP 版本 0.3.59-dev / versionCode 359；签名验证及产物上传均成功，artifact ID 14986527586。
- 当前候选版本：0.3.60-dev / versionCode 360；开发分支 design/impeccable-native-audit-781。
- Impeccable 固定上游提交：b49177d32788f2dc585c42831d845dacf1ebed2c。
- 本轮新增：KeyboardView 按键虚拟无障碍节点、CandidateBarView 候选词虚拟节点与候选横向辅助滚动；保留现有触摸、手势和动画实现。
- 784 号已完成；本轮最新代码提交后触发下一轮固定签名构建，编号以 Actions 实际记录为准。
- PanelView 功能面板虚拟节点、TalkBack 焦点顺序、字体缩放、横竖屏/分屏、动态效果性能与全部自定义项仍待继续处理/真机验收。

---

## 2026-11-25：PanelView 触摸探索命中修复

- 当前分支：`design/impeccable-native-audit-781`。
- 869 号工作流（run ID `45608206745`）已完成：Release APK 构建、签名验证、Artifact 上传均成功；对应 APP `0.3.73-dev` / versionCode `30073`。
- 源码修复：`PanelView.accessibilityNodeIdAt` 先检查具体子控件，再检查滚动容器。此前滚动视口覆盖整个内容区且在命中顺序中优先，触摸探索可能把网格单元、开关和滑块误命中为滚动容器。
- 本轮提交：`b59b48c19d521aa3e39d8f2b50bf379d553622fe`。
- APP patch 版本递增至 `0.3.74-dev` / versionCode `30074`；版本提交：`e1e1baafffc405ffe0874d279ca1736a1afb4874`。
- 本轮新版本尚未构建；待 Release 编译、签名验证与产物上传。TalkBack 触摸探索、线性焦点导航、Switch Access 仍需真机验证。
- A 阶段继续开放：SideColumn 侧边拼音列、各 Canvas 面板节点覆盖与操作语义、工具栏排序辅助操作仍需审查；B/C/D 及真机回归未完成。
