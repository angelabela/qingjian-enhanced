## 2026-11-25：Impeccable 原生 UI 审查与首轮优化

- 已安装测试的基线：781 号工作流，APP 版本 0.3.58-dev / versionCode 358，提交 6d5ee1084cba4d7bd0493d7ff4e6df1e1d56aa94。
- 本轮候选版本：0.3.59-dev / versionCode 359；开发分支 design/impeccable-native-audit-781。
- Impeccable 固定上游提交：b49177d32788f2dc585c42831d845dacf1ebed2c。
- 本轮修改：Canvas 面板文字系统缩放、分段选择/生词筛选/底部导航触摸区域、主题卡片选中与辅助语义；新增 PRODUCT.md、DESIGN.md 和原生 UI 审查报告。
- GitHub Actions #783 正在构建；构建、签名验证与 APK 产物状态以 Actions 实际结果为准。
- TalkBack、字体缩放、横竖屏/分屏、动态效果性能与原有自定义项仍待用户真机验收。

---

## 2026-11-25：Impeccable 原生 UI 审查与首轮优化

- 开发基线：781 号工作流，分支 fix/723-wubi-text-effects-space-gesture，提交 6d5ee1084cba4d7bd0493d7ff4e6df1e1d56aa94。
- 当前 Android 源码版本：0.3.58-dev；本轮目标版本：0.3.59-dev（仅在固定签名构建成功后作为可用版本交付）。
- 优化分支：design/impeccable-native-audit-781。
- Impeccable 固定上游提交：b49177d32788f2dc585c42831d845dacf1ebed2c。
- 本轮：保留主题与自定义项，修正 Canvas 面板文字系统缩放、分段选择/生词筛选触摸区域与导航选中语义；补充 PRODUCT.md、DESIGN.md 与完整审查报告。
- 状态：源码修改待 CI；TalkBack、字体缩放、横竖屏和动效流畅度仍待真机验收。

---

## 0.3.21-dev 修复轮次（2026-11-23，基于 551 号实机反馈）

- 开发基线：分支 `feature/workflow-444-model-coordination`，父提交 `f368db25ae747a08ab2a06023514e73fba6b6d50`；只修改青简输入法仓库，不触碰 main 或其他项目。
- 551 号实测仍存在三类问题：候选栏/拼音区静态底色不融合、视频背景未完整覆盖到底部功能区、最新剪贴板内容没有恢复到验证码显示区；扫码 Wi-Fi/网址提示仍在 5 秒后消失。
- 候选栏与拼音位图改为始终透明，由面板唯一背景层绘制主题底色或视频，避免 Rust 位图背景与面板 board 色形成双层。
- 视频模式同步更新底部功能栏透明状态；视频仍复用原有 TextureView/Surface，不因普通设置刷新重启播放器。
- 剪贴板提示只在相同内容仍显示时去重；输入组合结束后重新读取系统 primaryClip，避免提示被输入动作清除后无法恢复。
- 扫码网址/Wi-Fi 提示移除 5 秒自动关闭与临时抑制，保持可见直到点击或新内容替换。
- APP 版本：0.3.21-dev；按 Gradle 公式 versionCode=321。
- 最终应用源码提交：`5a2103dbcb8b85052d22156d73e48d73136db96e`；此前 #552–#554 对应中间提交，不作为最终测试包。
- 最终固定签名工作流：#555 / run ID `45100691585`，当前构建中；运行页：https://github.com/angelabela/qingjian-enhanced/actions/runs/45100691585。
- APP 版本：0.3.21-dev / versionCode 321。构建与签名验证尚未完成；完成后仍需用户实机验收。

---

