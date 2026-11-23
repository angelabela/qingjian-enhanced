## 最新固定签名 APK 交付（2026-11-23）

- 目标分支：`feature/workflow-444-model-coordination`
- 工作流：`android-signed-apk`，GitHub Actions 运行 **#560**（run ID `45107799200`）
- 源码提交：`16cf789c8e9a80e5003dd19c67b5d65cd57bb99a`
- 完成时间：2026-11-23 03:51:38 UTC
- APP 源码版本：`versionName=0.3.22-dev`；按当前 Gradle 公式计算 `versionCode=322`（在该提交的 `apps/android/core/Cargo.toml` 与 `apps/android/app/build.gradle.kts` 核对）
- 构建结果：`assembleRelease` 成功；固定签名验证步骤成功；APK Signature Scheme v2 验证通过
- 签名证书 SHA-256：`10be9cc45f931581203433b7c1095ca9689bb643463821ef9b108314aa958b72`（与既有固定签名证书一致）
- Artifact：`qingjian-android-signed`，ID `14788432762`，154,451,350 bytes；未过期，至 2026-12-23 03:51:29 UTC
- Artifact ZIP SHA-256：`2e077769e7b94e93a4bd429778a1349a9064a2814ec26cae763fb30feb0aba08`
- 运行页：https://github.com/angelabela/qingjian-enhanced/actions/runs/45107799200
- Artifact 下载入口：https://api.github.com/repos/angelabela/qingjian-enhanced/actions/artifacts/14788432762/zip
- 本次代码交付摘要：剪贴板提示按新剪贴板事件去重；视频背景层按面板宽度测量并调整填充；视频/光效/静态主题切换时同步候选文字对比色。
- 验证边界：CI 构建、签名和 Artifact 均已确认；BJ-065～BJ-067 仍待用户设备实测，未记为“用户验证通过”。
- 版本规则核对：用户指定稳定可用版本基线为 `0.3.4`、下一稳定版本应为 `0.3.5`；本次源码实际为 `0.3.22-dev`。保留源码 `-dev` 后缀，不将开发构建擅自改标为 `0.3.5`，稳定版本基线暂不推进，版本序列差异列为待处理事项。
- 下一步：设备安装验收 BJ-065～067；在下一次交付前先厘清用户稳定版本序列与当前 `0.3.22-dev` 源码版本的差异；另需修正 Debug 工作流实际执行 Release 构建的问题。

---

## 0.3.22-dev 修复轮次（2026-11-23，基于 555 号实机反馈）

- 仓库：`angelabela/qingjian-enhanced`；开发分支：`feature/workflow-444-model-coordination`。只在该分支修改，不触碰 `main` 或其他项目。
- 基线：555 号工作流所用源码提交 `5a2103dbcb8b85052d22156d73e48d73136db96e`；其后分支仅有 0.3.21-dev 状态记录提交。
- 反馈 1：候选显示区剪贴板内容重复出现。已将去重改为持久化内容哈希/时间戳，并由系统剪贴板变化事件放行真正的新复制事件；窗口隐藏时清除提示 UI，不清除已显示指纹。
- 反馈 2：视频背景右侧与底部未覆盖。已改为 MediaPlayer 居中裁切填充、清除 TextureView 额外矩阵，并按主面板实际宽度测量视频层；新增视频层尺寸诊断。
- 反馈 3：视频与背景光效切换时候选文字颜色异常。已增加候选栏自适应颜色统一入口，视频按帧亮度适配，光效按色组设置对比色，关闭动态背景恢复主题文字样式。
- APP 版本：0.3.22-dev；Gradle 版本公式对应 versionCode=322。
- 固定签名密钥与现有签名配置保持不变；本轮 Actions 构建、CI 签名验证及 APK 实机验收待完成。

