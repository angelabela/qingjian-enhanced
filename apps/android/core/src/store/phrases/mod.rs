//! 常用语面板的读写：列表、分组、增删改、移到最前都走 `qingjian_learning::PhraseBook`。
//! 不要句柄；改了之后壳再调会话的 `reload_phrases`，带输入码的常用语才进候选。

mod page;
mod request;
mod rules;

pub use page::PhrasePage;
pub use request::PhraseRequest;
pub use rules::PhraseRules;

use std::path::Path;

use qingjian_learning::{DEFAULT_GROUP, MAX_CODE_CHARS, PhraseBook};

/// 壳的编辑框按这份规则收文字，与落盘时的裁剪一致。
pub fn phrase_rules() -> PhraseRules {
    PhraseRules {
        default_group: DEFAULT_GROUP,
        max_code: MAX_CODE_CHARS,
    }
}

/// 跑一个请求，返回操作之后的整页（分组与全部常用语，靠前的常用）。
pub fn phrases(user_dir: &Path, request: PhraseRequest) -> PhrasePage {
    let mut book = PhraseBook::open(user_dir);
    let can_persist = book.can_persist();
    let mut operation_error = book.storage_error().map(str::to_owned);
    let result: Result<bool, qingjian_learning::LearningError> = match request {
        PhraseRequest::List => Ok(true),
        PhraseRequest::Add { text, group, code } => {
            if !can_persist {
                Ok(false)
            } else if text.trim().is_empty() {
                operation_error = Some("常用语内容为空：请先输入文字，再点击完成".to_owned());
                Ok(false)
            } else if book.is_full() {
                operation_error = Some("常用语数量已达到上限（10000 条），请先删除部分条目".to_owned());
                Ok(false)
            } else {
                let result = book.add(&text, &group, &code);
                if matches!(&result, Ok(false)) {
                    operation_error = Some("常用语未添加：内容无效或数量已达到上限".to_owned());
                }
                result
            }
        }
        PhraseRequest::Update { index, text, group, code } => {
            if can_persist { book.update(index, &text, &group, &code) } else { Ok(false) }
        }
        PhraseRequest::Remove { index } => {
            if can_persist { book.remove(index).map(|_| true) } else { Ok(false) }
        }
        PhraseRequest::Top { index } => {
            if can_persist { book.move_to_top(index).map(|_| true) } else { Ok(false) }
        }
    };
    let operation_succeeded = match result {
        Ok(succeeded) => succeeded,
        Err(error) => {
            tracing::warn!(%error, "常用语写不进去");
            operation_error = Some(format!("保存常用语失败：{error}"));
            false
        }
    };
    if !operation_succeeded && operation_error.is_none() {
        operation_error = Some("常用语存储当前不可用（只读模式或请求未执行）".to_owned());
    }
    PhrasePage {
        groups: book.groups(),
        phrases: book.phrases().to_vec(),
        operation_succeeded,
        operation_error,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_then_move_to_top() {
        let dir = std::env::temp_dir().join(format!("qj-android-phrases-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let add = |text: &str| {
            serde_json::from_str(&format!(
                r#"{{"op":"add","text":"{text}","group":"","code":""}}"#
            ))
            .unwrap()
        };
        assert!(phrases(&dir, add("一")).operation_succeeded);
        assert!(phrases(&dir, add("二")).operation_succeeded);
        let page = phrases(&dir, PhraseRequest::Top { index: 1 });
        assert!(page.operation_succeeded);
        assert_eq!(page.phrases[0].text, "一");
        assert_eq!(page.groups, [phrase_rules().default_group]);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn backs_up_corrupt_phrase_file_and_allows_new_entries_to_persist() {
        let dir = std::env::temp_dir().join(format!("qj-android-phrases-corrupt-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(qingjian_learning::PHRASE_BOOK_FILE);
        std::fs::write(
            &path,
            r#"[{"text":"原有短语","group":"默认"},{"text":"","group":"默认"},null]"#,
        ).unwrap();

        let page = phrases(
            &dir,
            PhraseRequest::Add {
                text: "测试常用语".to_owned(),
                group: String::new(),
                code: String::new(),
            },
        );
        assert!(page.operation_succeeded);
        assert_eq!(page.phrases.len(), 2);
        assert!(page.phrases.iter().any(|phrase| phrase.text == "原有短语"));
        assert!(std::fs::read_dir(&dir).unwrap().filter_map(Result::ok)
            .any(|entry| entry.file_name().to_string_lossy().contains(".corrupt-")));
        let reopened = phrases(&dir, PhraseRequest::List);
        assert_eq!(reopened.phrases.len(), 2);

        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn code_is_cut_to_the_shell_limit() {
        let dir = std::env::temp_dir().join(format!("qj-android-rules-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let code = "a".repeat(phrase_rules().max_code + 10);
        let request = PhraseRequest::Add {
            text: "长".to_owned(),
            group: String::new(),
            code,
        };
        let page = phrases(&dir, request);
        assert_eq!(page.phrases[0].code.len(), phrase_rules().max_code);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
