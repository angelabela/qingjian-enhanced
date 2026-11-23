## 0.3.17-dev · 2026-11-23 · beta

- 修复 Android 16 扫码悬浮服务使用未关联 Display 的 Service Context 创建 WindowContext 导致启动崩溃的问题
- 剪贴板普通内容与短信验证码统一去重；合并多入口延迟重试，防止同一事件重复消费，同时允许再次复制相同文字后重新提示
- 视频背景设置刷新时保留未变化 URI 对应的 TextureView/Surface，透明度与模糊效果原位更新，减少背景闪断或消失
- 视频背景层按面板实际宽度精确测量；扫码前缀输入框增强触摸焦点请求和窗口焦点等待
- APP 版本：0.3.17-dev；versionCode=317
- 本版待 GitHub Actions 构建、固定签名验证和用户设备验收

