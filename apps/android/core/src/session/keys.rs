//! 打字：按键、选词、删词、回车、空格与补全。

use super::Session;
use crate::frame::{Frame, FrameCandidate};

impl Session {
    /// 不在组句时按光标前的文字给补全（「用户名@」之后的邮箱后缀、刚打完的算式的结果），放进帧的候选；组句中不动。
    pub fn complete(&mut self, before_cursor: &str, after_cursor: &str) -> Frame {
        if !self.engine.composition().is_empty()
            || self.kana.as_ref().is_some_and(|kana| !kana.is_empty())
        {
            return self.refresh(String::new());
        }
        // 用光标两侧拼成当前编辑上下文：光标在刚上屏的词中间时，也保留这个词的联想。
        let context = format!("{before_cursor}{after_cursor}");
        let after_contact = self.after_contact.take();
        let after_commit = self.after_commit.take();
        self.cleared = None;
        let mut frame = self.refresh(String::new());
        // 只在刚刚选词上屏后短暂提供标点；单纯移动光标/刷新上下文时绝不常驻显示。
        let just_committed = after_commit.is_some();
        if just_committed
            && context.chars().any(|c| matches!(c as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF))
        {
            for punctuation in ["，", "。", "！", "？"] {
                frame.candidates.push(FrameCandidate::plain(punctuation));
                self.completions.push((0, punctuation.to_owned()));
            }
        }
        if let Some(name) = after_contact.filter(|name| context.contains(name.as_str())) {
            self.offer_contact(&name, &mut frame);
        }
        if let Some(text) = after_commit.filter(|text| context.contains(text.as_str())) {
            self.offer_follow_ups(&text, &mut frame);
        } else if let Some(previous) = qingjian_core::sentence::text_pieces(before_cursor, self.engine.language_model()).last() {
            self.follow_up_at = Some(self.completions.len());
            let words = self.engine.follow_ups_after(previous, 8);
            for word in words {
                frame.candidates.push(FrameCandidate::plain(&word));
                self.completions.push((0, word));
            }
        }
        let mut labels = Vec::new();
        for completion in qingjian_core::email_completions(before_cursor) {
            labels.push(completion.domain.to_owned());
            self.completions.push((0, completion.insert));
        }
        for completion in qingjian_core::calculation_completions(before_cursor) {
            labels.push(completion.label);
            self.completions
                .push((completion.replace, completion.insert));
        }
        frame
            .candidates
            .extend(labels.iter().map(|label| FrameCandidate::plain(label)));
        frame
    }

    pub fn push(&mut self, c: char) -> Frame {
        if let Some(kana) = &mut self.kana {
            kana.push(c);
            return self.refresh(String::new());
        }
        self.engine.push(c);
        self.refresh(String::new())
    }

    pub fn backspace(&mut self) -> Frame {
        if let Some(kana) = &mut self.kana {
            kana.backspace();
            return self.refresh(String::new());
        }
        // 点拼音行把光标放到了开头：屏幕键盘没有方向键挪回去，退格接着从末尾删，不卡住
        if self.engine.composition().cursor() == 0 {
            self.engine.move_cursor_end();
        }
        self.engine.backspace();
        self.refresh(String::new())
    }

    /// 点了拼音行第 `index` 个字之后（#881）：光标挪过去重查；对不上的写法（双拼外的解码、纠错、辅码）不动。
    pub fn move_caret(&mut self, index: usize) -> Frame {
        if self.kana.is_none()
            && let Ok(query) = self.engine.query()
            && self.engine.move_cursor_to_marked(&query, index)
        {
            return self.refresh(String::new());
        }
        self.frame(String::new())
    }

    /// 选中上一帧的第 `index` 个候选；拼音没吃完就接着组句。
    pub fn select(&mut self, index: usize) -> Frame {
        if !self.completions.is_empty() {
            let (replace, commit) = self.completions.get(index).cloned().unwrap_or_default();
            if self.follow_up_at.is_some_and(|at| index >= at) {
                return self.select_follow_up(&commit);
            }
            let mut frame = self.refresh(commit);
            frame.replace = replace;
            return frame;
        }
        if let Some(kana) = &mut self.kana {
            let commit = kana.candidates().get(index).cloned().unwrap_or_default();
            if !commit.is_empty() {
                kana.clear();
            }
            return self.refresh(commit);
        }
        if let Some(commit) = self.select_sentence(index) {
            return self.refresh(commit);
        }
        let Some(candidate) = self.candidates.items.get(index).cloned() else {
            return self.refresh(String::new());
        };
        let commit = self.engine.commit(&candidate);
        let mut frame = self.refresh(commit.clone());
        if self.engine.is_contact(&candidate.text) {
            self.offer_contact(&candidate.text, &mut frame);
        } else {
            self.offer_follow_ups(&commit, &mut frame);
        }
        frame
    }

    /// 上屏第 `index` 个候选的第 `sense` 条译文；学习与拼音消耗同选了这个候选。没有那条译文就不动。
    pub fn select_translation(&mut self, index: usize, sense: usize) -> Frame {
        let commit = self
            .candidates
            .items
            .get(index)
            .and_then(|candidate| self.engine.commit_translation(candidate, sense))
            .unwrap_or_default();
        self.refresh(commit)
    }

    /// 删掉第 `index` 个候选：用户词整删，词库词清学习记录；结果写进帧的 `notice`。
    pub fn forget(&mut self, index: usize) -> Frame {
        let Some(candidate) = self.candidates.items.get(index).cloned() else {
            return self.refresh(String::new());
        };
        let forgotten = self.engine.forget(&candidate);
        let text = &candidate.text;
        let notice = if forgotten.user_word {
            format!("已删除用户词「{text}」")
        } else if forgotten.learning {
            format!("已忘掉对「{text}」的学习记录")
        } else {
            format!("「{text}」是词库里的词，没什么可删")
        };
        let mut frame = self.refresh(String::new());
        frame.notice = notice;
        frame
    }

    /// 回车：敲的字母原样上屏；日语上屏转好的平假名（与日文输入法回车确定一致）。
    pub fn commit_raw(&mut self) -> Frame {
        if let Some(kana) = &mut self.kana {
            let hiragana = kana.hiragana();
            kana.clear();
            return self.refresh(hiragana);
        }
        let raw = self.engine.take_raw();
        self.refresh(raw)
    }

    /// 展开面板的「全部 / 单字」：切换单字模式（#848），只管这一段组句；没在组句时不变。
    pub fn toggle_single_chars(&mut self) -> Frame {
        self.engine.toggle_single_chars();
        self.refresh(String::new())
    }

    /// 清空组句（九宫格的「重输」）。
    pub fn clear(&mut self) -> Frame {
        if let Some(kana) = &mut self.kana {
            kana.clear();
        }
        self.engine.clear();
        self.refresh(String::new())
    }

    /// 组句中按空格：注音的音节还缺声调时空格是一声、进缓冲区（与桌面端一致），否则选第一个候选；
    /// 切不出候选就原样上屏。第一个候选没吃完拼音时选的词留在组句里（见 [`qingjian_core::Engine::take_held`]），接着选后面的。
    pub fn space(&mut self) -> Frame {
        if self.engine.zhuyin_needs_tone() {
            return self.push(' ');
        }
        let selectable = match &self.kana {
            Some(kana) => !kana.candidates().is_empty(),
            None => !self.candidates.items.is_empty(),
        };
        if selectable {
            self.select(0)
        } else {
            self.commit_raw()
        }
    }

    /// 清空后只刷新候选状态；撤销由 Android 删除键下滑手势处理，不显示在候选栏。
    pub fn cleared(&mut self, _text: &str) -> Frame {
        self.cleared = None;
        self.refresh(String::new())
    }

    /// 候选条上放着上屏名字后的电话邮箱：删除键、重输键先只收起它们，不动输入框里的字。
    pub fn has_offer(&self) -> bool {
        self.after_contact.is_some()
    }

    /// 九宫格拼音列里点了一个写法。
    pub fn pin(&mut self, alternative: &str) -> Frame {
        self.engine.pin_nine_key(alternative);
        self.refresh(String::new())
    }
}
