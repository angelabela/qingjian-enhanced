# PRODUCT.md — 青简输入法 Android

## Product
青简是一款以中文输入为核心、兼顾语言学习的 Android 输入法。Android 端由 Rust 输入引擎与 Kotlin 原生界面组成，键盘、候选栏、功能面板和设置页共同构成产品体验。

## Users
- 日常使用中文拼音、双拼、五笔等输入方案的 Android 用户。
- 需要候选联想、整句补全、翻译释义与生词记录的语言学习者。
- 希望自行调整键盘布局、字号、字重、主题、按键效果、背景和动效的深度定制用户。

## Primary jobs
1. 快速、准确、不中断地完成文字输入。
2. 通过候选栏、手势、工具栏和面板高效完成选词、编辑、剪贴板与常用语操作。
3. 按需启用云联想、整句补全、语音和翻译，不让非必要服务干扰基础输入。
4. 在不丢失个人设置的前提下，自定义键盘外观与交互。

## Platform
- Platform: Android native.
- Minimum SDK: 26; target SDK: 36 (source configuration).
- UI implementation: Android Views and custom Canvas drawing; not Jetpack Compose.
- Product mode: Operate. Input reliability, discoverability, accessibility and predictable interaction take priority over decorative novelty.

## Product constraints
- Rust Core owns input semantics and theme-derived keyboard palette; Kotlin is the Android presentation and interaction shell.
- Preserve package identity, existing signing configuration, user data compatibility and the complete set of existing customization settings.
- User-selected themes and effect styles must remain distinct. A design pass must not flatten them into one palette or animation.
- Keep typing latency and touch responsiveness ahead of decorative motion.
- Respect Android system insets, Back navigation, font scaling, TalkBack and touch target conventions.
- Treat build success, signature verification and real-device acceptance as separate verification stages.

## Voice
Clear, calm and direct. Use familiar Chinese labels, describe the current setting state, and make destructive or irreversible actions explicit.
