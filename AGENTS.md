# AGENTS.md

本仓库的通用架构、目录地图与编码约定见 CLAUDE.md，动手前先完整阅读。

## Android 项目规则

涉及 Android 输入法时，还必须阅读 PROJECT_RULES.md、apps/android/README.md 及对应的设计/实现文档。当前 Android UI 主要由原生 View 与 Canvas 自绘组成，不是 Compose；不要把网页检测器或浏览器预览当作 Android 原生验证。

## UI / UX 工作

本仓库通过固定版本的上游子模块引入 Impeccable，入口为 .agents/skills/impeccable/SKILL.md。审查 Android 原生界面时，使用其 reference/android.md、reference/audit.native.md、reference/critique-rubric.md 与 reference/craft-floor.md。

必须保留青简现有主题库、Rust Palette 配色来源、用户可调字号/字重、按键皮肤、透明度、磨砂、背景视频与光效、云层主题、动效参数、手势、工具栏排序和所有设置开关。优化布局与交互质量，不得把不同主题或效果统一成一种视觉样式，不得为追求 Material 外观而删除自定义能力。改动保持局部，并区分源码修改、CI 构建与真机验收。
