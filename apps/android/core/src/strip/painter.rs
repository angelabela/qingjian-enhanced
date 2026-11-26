//! 候选条的画笔：记住最新一帧要显示的内容，壳要画时按宽度倍数与深浅色出一张位图。

use qingjian_render::{FontLibrary, Frame as RenderFrame, Layout, Mode, Renderer, TextSizes, Theme};

use super::{StripHit, StripMeta, has_emoji, preedit, rows, visible_indices};
use crate::appearance;
use crate::error::AndroidError;
use crate::frame::Frame;

/// 中日同形字按简体选字形；学习语言是日语时译文里的汉字也用简体字形，与桌面端一致。
const LOCALE: &str = "zh-CN";

pub struct StripPainter {
    renderer: Renderer,

    light: Theme,

    dark: Theme,

    /// 字号档：候选字与译文一起的倍数，1 是主题自己的字号。
    text_scale: f32,

    /// 最新一帧要画的内容。
    content: RenderFrame,

    /// 单调递增的候选帧序号；随布局元数据传给 Android，拒绝旧帧回写。
    frame_id: u64,

    /// 渲染器的连续行下标 -> 原始候选下标（云候选被独立显示）。
    candidate_indices: Vec<usize>,

    /// 原始候选中有 emoji 的下标。
    emoji: Vec<usize>,

}

impl StripPainter {
    /// 加载字体（只按清单读几个系统字体文件，不扫目录）；在装配 Engine 的后台线程里建。
    pub fn new() -> Result<Self, AndroidError> {
        let renderer = Renderer::new(FontLibrary::system(LOCALE)?);
        // 高亮移动的过渡要壳按帧回调，候选条先不播
        Ok(Self {
            renderer,
            light: Theme::light().with_animations(false),
            dark: Theme::dark().with_animations(false),
            text_scale: 1.0,
            content: RenderFrame::default(),
            frame_id: 0,
            candidate_indices: Vec::new(),
            emoji: Vec::new(),
        })
    }

    /// 换主题（内置主题 id，没有的退回缺省）与字号档；下一帧起生效。
    pub fn set_appearance(&mut self, theme: &str, text_scale: f32, candidate_weight: u16) {
        let sized = |dark: bool| {
            let theme = appearance::theme(theme, dark);
            let base_sizes = appearance::text_sizes(&theme, text_scale);
            // 译文是较细小的辅助信息；略微提高字号，减少低分辨率屏幕上的发虚感。
            let sizes = TextSizes {
                candidate: base_sizes.candidate,
                annotation: theme.theme_size("annotation").map(|size| size * text_scale * 1.25),
            };
            theme
                .with_text_sizes(sizes)
                .with_candidate_weight(Some(candidate_weight))
                .with_animations(false)
        };
        self.light = sized(false);
        self.dark = sized(true);
        self.text_scale = text_scale;
    }

    /// 记下一帧要显示的拼音、提示与候选。
    pub fn show(&mut self, frame: &Frame) {
        self.frame_id = self.frame_id.saturating_add(1);
        self.candidate_indices = visible_indices(&frame.candidates);
        // Emoji 下标覆盖所有候选，包括被独立追加到 Kotlin 侧的云候选；
        // Kotlin 用自己的 wordRect 取得补充候选区域，再统一绘制彩色 Emoji。
        self.emoji = frame
            .candidates
            .iter()
            .enumerate()
            .filter(|(_, candidate)| has_emoji(&candidate.text))
            .map(|(i, _)| i)
            .collect();
        self.content = RenderFrame {
            preedit: (!frame.preedit.is_empty()).then(|| preedit(frame)),
            rows: rows(&frame.candidates, self.text_scale, |text, size| {
                self.renderer.measure_points(text, size)
            }),
            highlighted: None,
            footer: None,
            previous_page: false,
            next_page: false,
            sentence: None,
            status: (!frame.notice.is_empty()).then(|| frame.notice.clone()),
            mode: Mode::default(),
            columns: 0,
            column_ems: Vec::new(),
            reversed: false,
        };
    }

    /// 画最新一帧。`scale` 是 dp → 像素的倍数（`displayMetrics.density`）。
    pub fn paint(
        &mut self,
        highlighted: Option<usize>,
        scale: f32,
        dark: bool,
    ) -> Result<StripMeta, AndroidError> {
        // 空候选帧不进入栅格渲染器；无可绘制内容时返回空元数据，避免生成 0×N 位图。
        if self.content.rows.is_empty()
            && self.content.preedit.is_none()
            && self.content.status.is_none()
        {
            return Ok(StripMeta {
                frame_id: self.frame_id,
                width: 0,
                height: 0,
                candidates_top: 0,
                background: 0,
                hits: Vec::new(),
                preedit_edges: Vec::new(),
                preedit_top: 0.0,
                preedit_bottom: 0.0,
                emoji: Vec::new(),
            });
        }
        // 防止无效 density 进入渲染器的像素尺寸换算。
        let scale = if scale.is_finite() && scale > 0.0 { scale } else { 1.0 };
        self.content.highlighted = highlighted.and_then(|original| {
            self.candidate_indices
                .iter()
                .position(|&index| index == original)
        });
        let theme = if dark { &self.dark } else { &self.light };
        let snapshot = self.renderer.layout_only(&self.content, Layout::Strip, theme, scale)?;
        let width = snapshot.width.ceil().max(0.0) as u32;
        let height = snapshot.height.ceil().max(0.0) as u32;
        if width == 0 || height == 0 {
            self.last = None;
            return Ok(StripMeta {
                frame_id: self.frame_id,
                width: 0,
                height: 0,
                candidates_top: 0,
                background: 0,
                hits: Vec::new(),
                preedit_edges: Vec::new(),
                preedit_top: 0.0,
                preedit_bottom: 0.0,
                emoji: Vec::new(),
            });
        }
        let background = theme.variable("surface").map_or(0, appearance::argb);
        let meta = StripMeta {
            frame_id: self.frame_id,
            width,
            height,
            candidates_top: snapshot.candidates_top().ceil().max(0.0) as u32,
            background,
            hits: snapshot
                .hits
                .iter()
                .filter_map(|region| StripHit::new(region, (0, 0)))
                .map(|mut hit| {
                    // renderer 回报过滤后连续的显示下标；壳的选择、emoji 与长按仍使用原始候选下标。
                    if let Some(&original) = self.candidate_indices.get(hit.candidate) {
                        hit.candidate = original;
                    }
                    hit
                })
                .collect(),
            preedit_edges: snapshot.preedit.as_ref().map_or_else(Vec::new, |hit| hit.edges.clone()),
            preedit_top: snapshot.preedit.as_ref().map_or(0.0, |hit| hit.y),
            preedit_bottom: snapshot.preedit.as_ref().map_or(0.0, |hit| hit.y + hit.height),
            emoji: self.emoji.clone(),
        };
        Ok(meta)
    }

    /// 兼容旧 JNI 符号；布局快照不生成可复制的位图像素。
    pub fn copy_pixels(&self, _out: &mut [u8]) -> bool { false }
}
