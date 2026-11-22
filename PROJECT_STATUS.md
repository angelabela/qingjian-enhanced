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

- 最新交付并安装测试的 APK：GitHub Actions #528，签名 Release 构建成功、固定签名验证通过。用户实测反馈：视频背景仍只覆盖面板部分区域；扫码标题栏仍有透明感且与预览区有留白；前缀输入框需要多次点击才能进入可输入状态。
- APP 下一测试版本：`0.3.11-dev`，`versionCode=30311`（本轮待签名构建）（`apps/android/core/Cargo.toml` 为版本来源；`apps/android/app/build.gradle.kts` 按 主*10000+次*100+修订 计算）。保留 `-dev` 后缀。
- 工作流：`android-signed-apk` #527；run ID `45039384235`；提交 `6c298a4315d39ec165f1404a5d7a51e2df9b5b2f`；Build、固定签名和 Verify 步骤均成功；artifact `qingjian-android-signed`，ID `14760888930`，ZIP SHA-256 `6d4863fb3da13913b2d3dc7e35a0dfe9e3733df205302cdc4ebc9023635dc9d3`。
- APK：`qingjian-android-signed.apk`，188,659,509 bytes；APK SHA-256 `66216ed8d78893c7b0fa0fcb08c88858e75e5eb1a59438dc17b064db97d7bb6f`。签名证书 CN=Qingjian，SHA-256 `10be9cc45f931581203433b7c1095ca9689bb643463821ef9b108314aa958b72`，与 #516 固定证书一致。工作流链接：https://github.com/angelabela/qingjian-enhanced/actions/runs/45039384235；artifact 下载入口：https://api.github.com/repos/angelabela/qingjian-enhanced/actions/artifacts/14760888930/zip
- 本轮改动（待构建）：强制视频 TextureView 按 Stack 实际宽度精确测量，避免只按纹理固有尺寸显示在左侧；扫码标题栏改为完全不透明主题底色；前缀编辑增加窗口焦点/输入框焦点/IME 请求状态日志，并在软键盘请求未被接受时自动重试，重复点击可补发请求。
- 用户约定：可用 APP 版本从 `0.3.4` 基线逐次递增 patch；当前已交付版本 `0.3.10-dev` / `30310`，本轮待构建版本 `0.3.11-dev` / `30311`。版本号与 workflow run number 独立；不移除 `-dev` 后缀。
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
