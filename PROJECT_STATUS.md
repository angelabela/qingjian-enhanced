## 紧急修复轮次（2026-11-23，准备发布 0.3.19-dev）

- 当前分支基线：0.3.18-dev / versionCode 318，紧急回退自 #546；只操作 `feature/workflow-444-model-coordination`，不修改 main 或其他仓库。
- 0.3.17 输入法服务崩溃根因：`QingjianInputMethodService` 在 Kotlin 成员初始化阶段读取 Service 的 `mainLooper`，Context 尚未附加，触发 NullPointerException。0.3.18 回退已移除该过早初始化；本轮保持安全生命周期实现。
- 扫码服务崩溃根因：Service Context 没有关联 Display 就调用 `createWindowContext`。本轮改为先从 DisplayManager 获取默认 Display，创建 Display Context 后再创建悬浮窗口 Context。
- 视频背景：URI 不变时不再销毁 TextureView/Surface；透明度与模糊原位更新；只有视频源改变才替换视图。
- 剪贴板：保留 0.3.18 已回退的安全实现；本轮不宣称短信验证码或所有剪贴板边界已验收。
- 设置 Activity 闪退：现有日志没有足够的 Activity 崩溃堆栈，仍需独立采集 AndroidRuntime 日志，不将其与 IME Service NPE 混为一谈。
- 新版本目标：0.3.19-dev；versionCode 应按项目 Gradle 公式递增到 319。构建、签名与设备测试均待完成。
- 验收顺序：设置界面启动、IME 弹出、扫码悬浮窗、视频背景连续性/铺满、剪贴板与短信验证码。
- 版本号规则：仅生成可交付新版时递增 patch；本次修复提交对应 0.3.19-dev。
- 项目边界：仅修改青简输入法仓库 `angelabela/qingjian-enhanced` 的开发分支，不触碰 main 或其他项目。

---

## 最新修复轮次（2026-11-23，0.3.17-dev / versionCode 317）

- 基线：#546 / commit 248912e40e009fb7f98a57126d40b2acbf6ab290；设备日志 ime.2026-11-23.log（main，blob c10246d1da0cdcc60bc231be345c3150ba7f6636）。
- 扫码崩溃根因由堆栈确认：Service Context 未关联 Display，却直接调用 createWindowContext；本轮改为先 createDisplayContext(defaultDisplay)，再创建悬浮窗 WindowContext。
- 剪贴板：普通内容与短信验证码统一按 SHA-256 + 系统剪贴板 timestamp 去重；多个生命周期、监听器和验证码延迟任务合并为一个可取消队列。
- 视频背景：URI 未变化时保留 TextureView/Surface，透明度和模糊效果原位更新；视频层按面板实际宽度精确测量。
- 扫码前缀：增加触摸焦点请求，延长窗口焦点与软键盘请求等待上限。
- 当前阶段：源码已提交；构建、固定签名验证及设备实测均待完成。CI 成功不等同于设备验收。
- 仓库边界：只操作 angelabela/qingjian-enhanced 的 feature/workflow-444-model-coordination 分支。

---

## 最新开发修复（2026-11-23）

- 当前代码基线：0.3.16-dev；按 apps/android/app/build.gradle.kts 公式计算 versionCode=316。上一版 0.3.15-dev / 315 对应已成功签名交付的 Actions #545。
- 本轮基于 #545 设备反馈处理三项：扫码服务初始化与 CameraX 绑定增加异常保护/堆栈记录；视频变换改为完整画面铺满且不裁切；剪贴板相同内容通过持久化哈希只提示一次。
- 本轮修改尚待 Actions 构建、固定签名验证和设备实测；不可将源码提交或 CI 成功视为设备功能已验收。
- 设备日志 ZIP 中没有这次扫码崩溃的 FATAL EXCEPTION 堆栈；0.3.16-dev 已增加错误堆栈日志，若仍崩溃需重新导出日志以确认最终异常来源。
- 仓库边界不变：只操作 angelabela/qingjian-enhanced 的 feature/workflow-444-model-coordination 分支。

## 最新日志分析与修复（2026-11-23）

- 上传日志包包含本地 11 月 23 日日志；当前分支日志轮转代码已按 Asia/Shanghai 系统本地日期工作，保留 30 天，无需重复修改。
- 最新成功交付基线：#542 / APP 0.3.13-dev / versionCode 313；0.3.14-dev / versionCode 314 的构建需核对对应 run SHA；本轮新开发版本 0.3.15-dev / versionCode 315。
- 剪贴板提示修复重复内容在提示自动消失后被永久去重的问题；扫码悬浮窗 UI 改用 WindowContext，前缀框先请求 View 焦点再等待窗口焦点；Activity 扫码标题栏改为不透明；视频背景增加父容器与纹理层测量诊断。
- 设备端仍待安装测试；本次日志没有短信验证码接收事件，短信链路尚未验收。

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

- 当前开发中的下一版：`0.3.14-dev`，按当前 Gradle 公式计算 `versionCode=314`。尚未构建成功前，不视为可安装交付。
- 用户版本规则：以 `0.3.4` 为起始基线；每次成功交付可用 APK 后递增 patch 位；保留源码中的 `-dev` 后缀。APP 版本号与 GitHub Actions run number 分开管理。
- 最新成功构建、固定签名验证通过且 artifact 可用的 APK：`versionName=0.3.13-dev`（#542）。按当前 `apps/android/app/build.gradle.kts` 的公式计算，`versionCode=313`。
- 最新交付：`android-signed-apk` #538；run ID `45049084378`；分支 `feature/workflow-444-model-coordination`；提交 `0d30a9de4196ddb29e56e25f770ffc65a7f6fdfd`；完成时间 `2026-11-22T17:28:45Z`。Build、Verify APK signature、Upload signed APK 均成功。
- 固定签名：APK Signature Scheme v2 验证通过；证书 SHA-256 `10be9cc45f931581203433b7c1095ca9689bb643463821ef9b108314aa958b72`，与既有固定签名证书一致。
- Artifact：`qingjian-android-signed`；ID `14765077119`；大小 `154,488,827 bytes`；ZIP SHA-256 `d3f5979d7815f26f261c312a0ef4cea50e358c159a6edadcbed895e858c066b8`；有效期至 `2026-12-22T17:28:36Z`。运行页：https://github.com/angelabela/qingjian-enhanced/actions/runs/45049084378；artifact 下载入口：https://api.github.com/repos/angelabela/qingjian-enhanced/actions/artifacts/14765077119/zip
- #538 提交改动：扩展剪贴板监听、输入法焦点、扫码前缀和相机帧诊断日志；日志行增加线程名与 elapsed time；日志包清单增加文件行数和导出条目统计；移除不存在的 JNI `Native.flushLogging()/Dispatch.flush()` 调用，按同步写入的 RollingFileAppender 记录导出边界。
- 上一份已核实成功交付为 #532：源码版本 `0.3.10-dev`，按同一公式计算 `versionCode=310`；run ID `45044951413`，提交 `3d1e1538342e7dd18320d195644b340a81633a15`，artifact ID `14763401892`。#533–#537 因 Rust 日志 flush API 编译错误失败；#538 已通过完整构建与签名验证。
- 本次 APK 构建的应用源码提交为 `0d30a9de4196ddb29e56e25f770ffc65a7f6fdfd`，`apps/android/core/Cargo.toml` 版本为 `0.3.12-dev`。随后仅将档案文件提交为 `3b25cc1106ebfcd9e34e5588d55e7f48a37b4818`（PROJECT_STATUS）、`9372f35fdc76d4b8ef800aee0c13a965dd914d0d`（BUG_TRACKER）和 `011b48054c476ddeb0e70ceacb1857cc41ecaf7b`（CHANGELOG）；当前分支 HEAD 为最后一个档案提交。未修改应用源码或工作流。
- 版本序列需继续核对：已核实 #532 为 `0.3.10-dev`，#538 为 `0.3.12-dev`，本次检查未找到成功交付的 `0.3.11-dev` APK。旧档案中 `30310/30312` 的 versionCode 记录与当前 Gradle 公式不一致；当前源码公式计算值分别为 `310/312`。按最新成功交付版本，下一次新可用 APK 应为 `0.3.13-dev`，按当前公式计算 versionCode=313；但需先核对缺失的 `0.3.11-dev` 记录。`PROJECT_RULES.md` 仍保留旧快照 `0.3.4-dev/30400`；本次没有开发规则变更，因此按要求未修改该文件。未擅自改动应用源码或版本配置。
- Debug 监控：最新名为 `android-debug` 的运行仍是 #571（分支 `feature/android-optional-trigram-model`，提交 `1fc9c71979415edd8626746bd5fd48d93d34f08c`，2026-11-21T12:21:48Z 完成）。该工作流实际执行 `assembleRelease`、固定签名和签名验证，artifact 也是 `qingjian-android-signed`；目标分支未列入该工作流 push 分支范围，因此当前目标分支没有真正的 Debug APK 运行。

## 当前已知待验证事项

1. BJ-041 视频背景铺满、BJ-042 扫码标题栏不透明且与预览区无缝衔接、BJ-043 前缀输入框首次点击获得焦点：#538 版本仍待设备确认；本轮继续调整标题栏透明度与预览区动态对齐，并增加视频、剪贴板和前缀输入诊断日志。
2. CI-001：#533–#537 的 `Dispatch.flush()` Rust 编译错误已在 #538 代码中移除并通过构建；CI 层面已验证，设备端日志导出仍待用户验证。
3. REL-001：版本号序列与旧档案 versionCode 记载不一致，需在下一次交付前核对；不得把 workflow run number 当作 APP 版本。
4. CI-002：`android-debug.yml` 名称与实际 `assembleRelease` 行为不符，且未覆盖当前目标分支；尚未生成经确认的真正 Debug APK。
5. BJ-001 至 BJ-010 等既有设备问题仍以 BUG_TRACKER 中的逐项状态为准；没有用户确认的项目不得标为“用户验证通过”。

## 下一步事项

- 本轮 0.3.15-dev 已修复剪贴板相同内容重复显示逻辑，并改用 WindowContext 处理悬浮扫码窗口焦点/Display Context；视频背景增加完整布局尺寸诊断，待新日志确定是否仍需改布局。日志轮转源码已按本地日期命名并保留 30 天。
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
