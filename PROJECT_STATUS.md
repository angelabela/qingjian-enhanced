# 青简输入法项目状态档案

> 本文件是持续开发的入口。每次开始开发先读本文件、PROJECT_RULES.md、BUG_TRACKER.md、CHANGELOG.md，再核对 GitHub 当前分支、源码和最新工作流。源码与工作流事实优先于本文件；发现不一致时先修正档案。

## 项目身份

- 项目：青简输入法 Android
- 仓库：`angelabela/qingjian-enhanced`
- 当前开发分支：`feature/workflow-444-model-coordination`
- Android 工程：`apps/android`
- Android Kotlin 应用：`apps/android/app`
- Rust Android 核心：`apps/android/core`
- 构建工作流：`.github/workflows/android-debug.yml`
- 只修改青简输入法项目；不得触碰同账户的「网络医生」或其他仓库。

## 当前版本基线

- 最近交付给用户测试的 APK：492 号工作流，签名 Release 构建成功并已下载交付；用户尚未反馈本轮功能实测结果。
- APP 测试版本：`0.3.5-dev`，`versionCode=30500`（由 `apps/android/core/Cargo.toml` 与 `apps/android/app/build.gradle.kts` 核对）。保留 `-dev` 后缀，没有擅自改为正式版。
- 工作流：`android-signed-apk` #492；run ID `45020293420`；提交 `0b5e3ec9d1f53e130174fd5f3540563bbd501baf`；签名验证步骤通过；artifact `qingjian-android-signed`，ID `14753507016`，ZIP SHA-256 `02d23465504301a77650447dfd5e47ca0900507841e3393d36dc6e37b7faa497`。
- APK：`qingjian-android-signed.apk`，188,659,509 bytes。工作流链接：https://github.com/angelabela/qingjian-enhanced/actions/runs/45020293420
- 本轮四项功能已进入签名测试包：扫码标题栏整区拖动；扫码识别 Wi-Fi/网址后 5 秒提示及相应动作；剪贴板 Wi-Fi/网址快捷操作图标与滑动删除；独立按键效果页（透明度、磨砂、26 字母跑马灯）。均待用户安装实测。
- 用户约定：可用 APP 版本按 `0.3.4` 基线逐次递增 patch；当前测试构建 `0.3.5-dev` / `30500`。后续版本仍须核对所有版本来源与发布约定，不能擅自移除 `-dev`。
- APP 版本号与 GitHub Actions 工作流编号完全独立，不得互相替代。

## 当前已知待验证事项

486 号 APK 安装测试后，用户此前报告的三项问题需要以用户最新实测结果为准：
1. 扫码悬浮窗口标题栏应跟随当前主题色。
2. 验证码/剪贴板区域应显示系统剪贴板最新复制内容，而不是旧历史内容。
3. 翻译面板切换或打开后应保留动态光效和视频背景，不应退回默认静态配色。

这些项目的 486 修复已经提交并构建，但**尚不能仅凭构建成功认定用户设备上已修复**。等待用户实测确认；若仍存在，回查 486 实际源码、调用链和历史提交，不能照搬同一修复。

## 已确认的项目方向与功能边界

- 产品为 Android 中文输入法，重视稳定输入、候选栏/键盘绘制、主题适配、手势、剪贴板、扫码与翻译等体验。
- 用户明确要求保留：云端大模型相关能力、云联想、整句补全。
- 当前已知状态：GIF 表情功能已去掉；AI 智能表达功能已去掉；语音输入动画已恢复默认动画。不得擅自把这些功能重新列为保留或待开发功能。
- 用户通常通过手机 GitHub 操作，优先直接完成仓库修改、Actions 构建与 APK 交付；说明步骤时需适合手机端操作。
- APK 必须沿用仓库现有固定签名密钥/Secrets；禁止生成新密钥、替换密钥或修改签名配置，除非用户明确授权。
- 不得因青简输入法开发而影响同账户的其他项目。

## 每轮开发结束必须更新

- 当前 APP 版本与 versionCode
- 最新工作流编号、run URL、状态和 artifact
- 本轮提交 SHA 与修改文件
- 已解决 / 未解决 / 待用户验证的 Bug 状态
- 构建、签名检查结果；明确区分 CI 成功与设备实测成功
