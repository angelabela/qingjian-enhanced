---
name: 青简输入法 Android
description: 高度可定制、以输入效率为核心的 Android 中文输入法
typography:
  title:
    fontFamily: "Android system sans-serif / user-configured typeface"
    fontSize: "17sp"
    fontWeight: 700
  body:
    fontFamily: "Android system sans-serif / user-configured typeface"
    fontSize: "16sp"
    fontWeight: 400
  secondary:
    fontSize: "13sp"
    fontWeight: 400
  caption:
    fontSize: "12sp"
    fontWeight: 400
rounded:
  control: "9dp"
  card: "12dp"
  panel: "16dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
components:
  setting-row:
    height: "48dp minimum"
    padding: "8dp 16dp"
  segmented-option-hit-area:
    height: "48dp"
  bottom-tab-hit-area:
    height: "48dp minimum"
---

# Design System: 青简输入法 Android

## Overview
青简 Android 是一个以输入为中心的 Operate 界面。键盘是高频操作表面，设置 App 是管理和调整工具；二者共享主题来源，但设置页的阅读层级应保持安静、清楚，不与键盘的动态效果争夺注意力。

设计原则是“输入效率稳定，视觉表达由用户决定”。系统提供一致的间距、文字层级、触摸反馈和状态表达；用户选择的主题、颜色、按键皮肤、透明度、磨砂、背景、云层与动效仍由用户掌控。优化不能以牺牲这些选择为代价。

Key characteristics:
- 输入优先，装饰动效不得阻塞触摸与选词。
- 主题与配色以 Rust Palette 为来源，深浅色和各主题独立保留。
- 设置页使用清晰分组、紧凑卡片与单列滚动结构。
- 自绘界面仍需遵守 Android 的可访问性与系统设置。

## Colors
颜色不是一套固定的 App 十六进制色板，而是按用户当前主题由 Rust Native.keyboardPalette 提供。Android Palette 映射 board、surface/key、functionalKey、accent/pressed、brand、onBrand、keyShadow、text、gloss、faint、fresh 与 cloud。

App 设置页的 AppColors 从同一 Palette 派生背景、表面、强调色、次级文字和分隔线；次级文字在背景上低于 4.5:1 时回退到主文字色，弱化文字至少检查 3:1。不要在 Kotlin 页面另建一套固定主题色，也不要将所有主题的品牌色或动态背景效果改成相同值。

按键边框、按键背景、背景光效和视频画面中的颜色属于用户可配置效果，不应被设置页的中性色规范覆盖。

## Typography
设置页使用 Android 系统 sans-serif 与用户选定的字重。主要标题约 17sp，设置正文约 16sp，次级说明约 13sp，辅助标签约 12sp。文字使用 sp；自绘 Canvas 的文字也必须使用 scaledDensity，而不是只乘 display density。

候选栏字号、键面字号与键盘字重有独立的用户设置，不得被 App 设置页的字号规范覆盖。长文案优先换行或省略，不得压缩到难以阅读的字号。

## Layout
App 首页采用固定标题区、可滚动内容区和底部三项导航。内容区左右留白约 16dp，分组间距约 24dp，卡片内部留白约 16dp。二级设置页保留固定顶栏与独立滚动区，并正确处理状态栏、导航栏、刘海和 IME insets。

键盘、候选栏和面板随可用窗口尺寸及用户设置的键盘高度变化。面板内容应在短屏幕上可滚动；长列表应避免一次性创建大量重型 View。主题选择保留两列预览布局，但需要在窄窗口与大字体下检查换行和裁切。

所有可点击的普通设置项、筛选项和导航项以至少 48dp 的触摸命中区域为目标；视觉胶囊可以更紧凑，但不可把命中区域一起缩小。

## Elevation & Depth
设置页以背景、卡片表面和细分隔线建立层级；选中态允许轻微抬升。不要给每个卡片同时叠加粗边框、重阴影和光晕。键盘按键阴影、玻璃、磨砂和云层体积光属于独立效果系统，按用户开关与主题配置工作。

## Shapes
设置卡片约 12dp 圆角，面板卡片约 16dp，分段选择与筛选胶囊约 9–16dp。开关保持当前 PillSwitch 的识别性。不同层级可有不同圆角，不要把所有元素做成药丸形。

## Components
- 设置入口行：图标、标题、当前值和导航提示；整行可点击，触摸高度不低于 48dp。
- 分段选择：选中态清楚；每个选项的视觉高度可保持紧凑，但独立命中区至少 48dp。
- 底部导航：首页、生词本、设置；图标与文字同时呈现，当前项提供 selected 语义。
- 主题预览：展示候选条和键盘色彩，选中主题有明确边框与可访问名称。
- 键盘与候选栏：以用户设置为最终显示来源；滑动、上滑、下滑、长按等手势必须互不抢占。
- 动态背景与云层：每个样式保持独立视觉身份；视频和昂贵效果不应在不可见时持续消耗资源。

## Do's and Don'ts
- Do preserve every existing setting, theme, effect, gesture, toolbar arrangement and user preference.
- Do use Rust Palette roles and semantic state colors rather than introducing a parallel fixed palette.
- Do scale Canvas text with Android font scale and test large font settings.
- Do provide 48dp hit areas, visible selected states and meaningful accessibility labels.
- Do keep loading, error, empty and success feedback close to the action that caused them.
- Don't replace Android Views/Canvas with a new UI framework as part of a polish pass.
- Don't unify distinct cloud scenes, background effects, key skins or user-selected colors.
- Don't add animation only for decoration; keep motion short, interruptible and lifecycle-aware.
- Don't claim TalkBack, tablet, landscape or frame-rate verification without device evidence.
