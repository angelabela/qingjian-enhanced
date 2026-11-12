# 青简输入法（Android 版）

这是青简输入法的 Android 专用开发仓库，当前只维护 Android 应用及其构建所需的 Rust 核心代码。

## Android 应用

- Android 工程：[`apps/android`](apps/android)
- Kotlin 输入法界面与设置：`apps/android/app`
- Rust 核心与 JNI：`apps/android/core`
- Android 构建工作流：[`.github/workflows/android-debug.yml`](.github/workflows/android-debug.yml)

## 自动构建 APK

在 GitHub 仓库的 **Actions → android-debug** 中查看构建记录。构建成功后，在对应运行记录页面的 **Artifacts** 下载 `qingjian-android-debug`，解压后安装 APK。

也可以向 `feature/smart-input-android-0.7` 分支推送提交，自动触发 Android Debug APK 构建。

## 本地构建要求

- JDK 17 或以上（CI 使用 JDK 21）
- Android SDK Platform 36
- Android NDK 29.0.13599879
- Rust 1.96.0
- `cargo-ndk` 4.1.2

详细构建说明见 [Android README](apps/android/README.md)。

## 项目许可

本项目代码遵循 [GPL-3.0-or-later](LICENSE)。第三方词库、模型及资源按各自许可使用。
