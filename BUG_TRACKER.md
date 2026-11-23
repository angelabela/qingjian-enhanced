# 2026-11-23：0.3.17-dev 日志驱动修复

## BJ-049：Android 16 扫码悬浮窗创建时崩溃
- 日志堆栈指向 ScanOverlayService.onCreate 第 186 行：Service Context 未关联 Display，createWindowContext 抛 UnsupportedOperationException。
- 修复：先从 DisplayManager 获取默认 Display，创建 Display Context，再创建 TYPE_APPLICATION_OVERLAY WindowContext。
- 验证：待 Android 16 设备实测。

## BJ-050：视频背景在面板显示过程中消失/不完整
- 日志显示视频层最初成功播放（1080×836），随后记录 video=0×0；该值可能表示视图对象为空，不能仅凭该尺寸认定 TextureView 被测量为零。
- 源码风险：每次 refreshDynamicBackground 都先移除 TextureView，再无条件创建新对象，可能释放 Surface 并造成闪断。
- 修复：URI 不变时保留 TextureView/Surface，只原位更新透明度、模糊和亮度遮罩；URI 改变才替换视图；视频层按面板精确宽度测量。
- 验证：待设备确认视频完整覆盖整个输入法面板且设置刷新时不中断。

## BJ-051：剪贴板与验证码提示重复消费
- 日志显示同一 timestamp 的 primaryClip 在短时间内多次 selected；生命周期、剪贴板监听和验证码路径存在独立重试入口，preferredCode 曾绕过普通哈希去重。
- 修复：合并为单一可取消重试队列；普通剪贴板与短信验证码统一按 SHA-256 + 剪贴板 timestamp 去重。再次复制相同文字且 timestamp 更新时仍可提示，不清空系统剪贴板。
- 验证：待设备测试键盘重开、重复回调、同文再次复制及短信验证码。

## BJ-052：扫码前缀输入框首次点击未唤起软键盘
- 日志显示 EditText 已有 View 焦点，但悬浮窗口没有 Window 焦点，showSoftInput 返回 false；再次点击后窗口才进入可聚焦状态。
- 修复：增加 requestFocusFromTouch，延长窗口焦点和 IME 请求等待上限。
- 验证：待 Android 16 设备首次点击输入及关闭编辑态后的宿主输入法行为。

