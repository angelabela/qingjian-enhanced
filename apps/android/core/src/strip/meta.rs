//! 一帧候选条的布局与命中元数据（JSON 过 JNI）；候选字形由 Android 统一绘制。

use serde::Serialize;

use super::StripHit;

#[derive(Clone, Debug, Serialize)]
pub struct StripMeta {
    /// 与候选内容同步的单调帧序号；Android 丢弃迟到的旧帧元数据。
    pub frame_id: u64,

    /// 布局快照逻辑宽高（像素单位）；不包含候选字形像素。
    pub width: u32,

    pub height: u32,

    /// 这一行以上是拼音行，壳钉在视口上；以下的候选随横向滚动。
    pub candidates_top: u32,

    /// 主题底色（ARGB），由 Android 绘制背景时使用。
    pub background: u32,

    /// Rust 布局中实际参与排版的本地候选原始下标；Android 必须逐项找到有效主矩形。
    pub candidate_indices: Vec<usize>,

    /// 当前 Rust 帧中完整候选文本快照；Android 用它拒绝同索引但不同内容的迟到布局。
    pub candidate_texts: Vec<String>,

    /// 可点区域，译文在前（先命中）。
    pub hits: Vec<StripHit>,

    /// 拼音行上各字之间的 x 坐标（布局像素），第 `i` 个是第 `i` 个字之前；没有拼音行时为空。
    pub preedit_edges: Vec<f32>,

    /// 拼音行的上下沿（布局像素），用于拼音行触摸命中。
    pub preedit_top: f32,

    pub preedit_bottom: f32,

    /// 候选词里有 emoji 的下标：渲染器画不出 COLRv1 的彩色 emoji，壳在词的位置上自己画。
    pub emoji: Vec<usize>,
}
