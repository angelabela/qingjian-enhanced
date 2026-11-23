## 最新修复轮次（2026-11-23，0.3.17-dev / versionCode 317）

- 基线：#546 / commit 248912e40e009fb7f98a57126d40b2acbf6ab290；设备日志 ime.2026-11-23.log（main，blob c10246d1da0cdcc60bc231be345c3150ba7f6636）。
- 扫码崩溃根因由堆栈确认：Service Context 未关联 Display，却直接调用 createWindowContext；本轮改为先 createDisplayContext(defaultDisplay)，再创建悬浮窗 WindowContext。
- 剪贴板：普通内容与短信验证码统一按 SHA-256 + 系统剪贴板 timestamp 去重；多个生命周期、监听器和验证码延迟任务合并为一个可取消队列。
- 视频背景：URI 未变化时保留 TextureView/Surface，透明度和模糊效果原位更新；视频层按面板实际宽度精确测量。
- 扫码前缀：增加触摸焦点请求，延长窗口焦点与软键盘请求等待上限。
- 当前阶段：源码修改准备提交；构建、固定签名验证及设备实测均待完成。CI 成功不等同于设备验收。
- 仓库边界：只操作 angelabela/qingjian-enhanced 的 feature/workflow-444-model-coordination 分支。

