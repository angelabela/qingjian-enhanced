//! 剪贴板历史里的一条：文字或图片。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClipboardEntry {
    /// 复制的文字，原样（图片条目为空）。
    #[serde(default)]
    pub text: String,

    /// 图片在 `clipboard-images/` 里的文件名；文字条目为 `None`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,

    /// 最近一次复制的 Unix 秒；旧版历史没有时间字段时按未知时间处理。
    #[serde(default)]
    pub at: i64,
}

impl ClipboardEntry {
    pub fn is_image(&self) -> bool {
        self.image.is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::ClipboardEntry;

    #[test]
    fn legacy_clipboard_entry_without_timestamp_defaults_to_zero() {
        let entry: ClipboardEntry =
            serde_json::from_str(r#"{"text":"legacy","image":null}"#).unwrap();
        assert_eq!(entry.at, 0);
    }
}
