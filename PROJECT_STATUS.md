# 青简输入法项目状态档案

> 本文件是持续开发的入口。每次开始开发先读本文件、PROJECT_RULES.md、BUG_TRACKER.md、CHANGELOG.md，再核对 GitHub 当前分支、源码和最新工作流。源码与工作流事实优先于本文件；发现不一致时先修正档案。

## 项目身份

- 项目：青简输入法 Android
- 仓库：`angelabela/qingjian-enhanced`
- 当前开发分支：`feature/workflow-444-model-coordination`
- Android 工程：`apps/android`
- Android Kotlin 应用：`apps/android/app`
- Rust Android 核心：`apps/android/core`
- 构建工作流：固定签名交付使用 `.github/workflows/android-signed-apk.yml`；`.github/workflows/android-debug.yml` 当前仍执行 `assembleRelease`，并非真正 Debug 构建。
- 只修改青简输入法项目；不得触碰同账户的「网络医生」或其他仓库。

## 当前版本基线与最新交付

- 用户版本规则：以 `0.3.4` 为起始基线；每次成功交付可用 APK 后递增 patch 位；保留源码中的 `-dev` 后缀。APP 版本号与 GitHub Actions run number 分开管理。
- 最新成功构建、固定签名验证通过且 artifact 可用的 APK：`versionName=0.3.12-dev`。按当前 `apps/android/app/build.gradle.kts` 的公式（主版本 × 10000 + 次版本 × 100 + 修订版本）计算，`versionCode=312`；该值来自源码计算，未下载 APK 直接读取 Manifest。
- 最新交付：`android-signed-apk` #538；run ID `45049084378`；分支 `feature/workflow-444-model-coordination`；提交 `0d30a9de4196ddb29e56e25f770ffc65a7f6fdfd`；完成时间 `2026-11-22T17:28:45Z`。Build、Verify APK signature、Upload signed APK 均成功。
- 固定签名：APK Signature Scheme v2 验证通过；证书 SHA-256 `10be9cc45f931581203433b7c1095ca9689bb643463821ef9b108314aa958b72`，与既有固定签名证书一致。
- Artifact：`qingjian-android-signed`；ID `14765077119`；大小 `154,488,827 bytes`；ZIP SHA-256 `d3f5979d7815f26f261c312a0ef4cea50e358c159a6edadcbed895e858c066b8`；有效期至 `2026-12-22T17:28:36Z`。运行页：https://github.com/angelabela/qingjian-enhanced/actions/runs/45049084378；artifact 下载入口：https://api.github.com/repos/angelabela/qingjian-enhanced/actions/artifacts/14765077119/zip
- #538 提交改动：扩展剪贴板监听、输入法焦点、扫码前缀和相机帧诊断日志；日志行增加线程名与 elapsed time；日志包清单增加文件行数和导出条目统计；移除不存在的 JNI `Native.flushLogging()/Dispatch.flush()` 调用，按同步写入的 RollingFileAppender 记录导出边界。
- 上一份已核实成功交付为 #532：源码版本 `0.3.10-dev`，按同一公式计算 `versionCode=310`；run ID `45044951413`，提交 `3d1e1538342e7dd18320d195644b340a81633a15`，artifact ID `14763401892`。#533–#537 因 Rust 日志 flush API 编译错误失败；#538 已通过完整构建与签名验证。
- 本次 APK 构建的应用源码提交为 `0d30a9de4196ddb29e56e25f770ffc65a7f6fdfd`，`apps/android/core/Cargo.toml` 版本为 `0.3.12-dev`。随后仅将档案文件提交为 `3b25cc1106ebfcd9e34e5588d55e7f48a37b4818`（PROJECT_STATUS）、`9372f35fdc76d4b8ef800aee0c13a965dd914d0d`（BUG_TRACKER）和 `011b48054c476ddeb0e70ceacb1857cc41ecaf7b`（CHANGELOG）；当前分支 HEAD 为最后一个档案提交。未修改应用源码或工作流。
- 版本序列需继续核对：已核实 #532 为 `0.3.10-dev`，#538 为 `0.3.12-dev`，本次检查未找到成功交付的 `0.3.11-dev` APK。旧档案中 `30310/30312` 的 versionCode 记录与当前 Gradle 公式不一致；当前源码公式计算值分别为 `310/312`。未擅自改动应用源码或版本规则，下一次交付前需统一版本序列和档案。
- Debug 监控：最新名为 `android-debug` 的运行仍是 #571（分支 `feature/android-optional-trigram-model`，提交 `1fc9c71979415edd8626746bd5fd48d93d34f08c`，2026-11-21T12:21:48Z 完成）。该工作流实际执行 `assembleRelease`、固定签名和签名验证，artifact 也是 `qingjian-android-signed`；目标分支未列入该工作流 push 分支范围，因此当前目标分支没有真正的 Debug APK 运行。

## 当前已知待验证事项

1. BJ-041 视频背景铺满、BJ-042 扫码标题栏不透明且与预览区无缝衔接、BJ-043 前缀输入框首次点击获得焦点：修复代码已进入 #538 签名 APK，均为**已修复待验证**，尚无用户设备实测结论。
2. CI-001：#533–#537 的 `Dispatch.flush()` Rust 编译错误已在 #538 代码中移除并通过构建；CI 层面已验证，设备端日志导出仍待用户验证。
3. REL-001：版本号序列与旧档案 versionCode 记载不一致，需在下一次交付前核对；不得把 workflow run number 当作 APP 版本。
4. CI-002：`android-debug.yml` 名称与实际 `assembleRelease` 行为不符，且未覆盖当前目标分支；尚未生成经确认的真正 Debug APK。
5. BJ-001 至 BJ-010 等既有设备问题仍以 BUG_TRACKER 中的逐项状态为准；没有用户确认的项目不得标为“用户验证通过”。

## 下一步事项

- 请用户安装 #538 后重点验证日志导出是否包含导出前最后时刻的日志、日志包中的 line_count 与最新时间记录是否正确。
- 同时验证 BJ-041、BJ-042、BJ-043 的实际设备表现；CI 成功不等于设备问题已解决。
- 下一次 APK 构建前，先核对 `0.3.11-dev` 是否有独立交付记录，并统一 `versionName`、Gradle 计算的 `versionCode` 与档案记录。
- 单独审查 Debug 工作流，使其真正执行 Debug variant；本次监控没有修改工作流或应用源码。

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
