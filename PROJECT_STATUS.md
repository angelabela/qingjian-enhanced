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

- 最新交付给用户测试的 APK：GitHub Actions #516，签名 Release 构建成功、签名验证通过、APK artifact 已下载并核对；本轮功能尚待用户设备实测。
- APP 测试版本：`0.3.8-dev`，`versionCode=30308`（`apps/android/core/Cargo.toml` 为版本来源；`apps/android/app/build.gradle.kts` 按 主*10000+次*100+修订 计算）。保留 `-dev` 后缀。
- 工作流：`android-signed-apk` #516；run ID `45033003050`；提交 `504187b0268c984a0a84d886e05776e6d02dfd56`；Build、固定签名和 Verify 步骤均成功；artifact `qingjian-android-signed`，ID `14758359215`，ZIP SHA-256 `29bbc4c868b7586aced0cc5a742e87e85ea9f5881e6ca5125cb7a2e3cbbecaba`。
- APK：`qingjian-android-signed.apk`，188,659,509 bytes；APK SHA-256 `1ab509cc1cce5447be91ce044e8beb2e75376f22b581765a0df17f28c828a93b`。工作流链接：https://github.com/angelabela/qingjian-enhanced/actions/runs/45033003050；artifact 下载入口：https://api.github.com/repos/angelabela/qingjian-enhanced/actions/artifacts/14758359215/zip
- 本轮交付功能：扫码前缀框重复点击焦点/IME 处理与标题栏—相机预览对齐修正；按键边缘发光、立体效果开关及即时刷新；剪贴板新内容提示覆盖候选栏、候选刷新不吞提示、延迟复读及未显示时不提前消费去重状态。全部待用户安装实测。
- 用户约定：可用 APP 版本从 `0.3.4` 基线逐次递增 patch；本次源码版本 `0.3.8-dev` / `30308`。版本号与 workflow run number 独立；不移除 `-dev` 后缀。
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
