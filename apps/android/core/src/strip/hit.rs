//! 候选条布局快照中的一块可点区域；候选文字由 Android 统一绘制。

use qingjian_render::{HitRegion, HitTarget};
use serde::Serialize;

/// 布局快照中的像素坐标；`sense` 有值时是这个候选下面的第几条译文。
#[derive(Clone, Debug, Serialize)]
pub struct StripHit {
    pub candidate: usize,

    pub sense: Option<usize>,

    pub x: f32,

    pub y: f32,

    pub width: f32,

    pub height: f32,
}

impl StripHit {
    /// 区域是内容区坐标，加上内容区在布局快照里的偏移。非候选控件返回 `None`；无效或非有限矩形也返回 `None`。
    pub fn new(region: &HitRegion, offset: (u32, u32)) -> Option<Self> {
        let (candidate, sense) = match region.target {
            HitTarget::Candidate(candidate) => (candidate, None),
            HitTarget::Translation { candidate, sense } => (candidate, Some(sense)),
            HitTarget::MenuItem(_) | HitTarget::Page(_) | HitTarget::Panel(_) => return None,
        };
        let x = region.x + offset.0 as f32;
        let y = region.y + offset.1 as f32;
        if !x.is_finite() || !y.is_finite() || !region.width.is_finite() || !region.height.is_finite()
            || region.width <= 0.0 || region.height <= 0.0 {
            return None;
        }
        Some(Self {
            candidate,
            sense,
            x,
            y,
            width: region.width,
            height: region.height,
        })
    }
}
