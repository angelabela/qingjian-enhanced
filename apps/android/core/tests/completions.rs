//! Android 会话里的邮箱与网址补全：候选显示完整目标，只向输入框追加尚未输入的后缀。

use std::path::Path;

use qingjian_android::Session;
use qingjian_core::Engine;
use qingjian_dictionary::Dictionary;

fn sample(name: &str) -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../assets/sample")
        .join(name)
}

fn session() -> Session {
    let engine = Engine::new(Dictionary::from_path(sample("dict.tsv")).unwrap());
    let user_dir = std::env::temp_dir().join(format!("qingjian-completions-{}", std::process::id()));
    Session::new(engine, sample("wubi86.tsv"), sample(""), user_dir)
}

#[test]
fn email_completion_is_available_in_android_session() {
    let mut session = session();
    let frame = session.complete("zhangsan@q");
    assert_eq!(frame.candidates.first().unwrap().text, "qq.com");
    let selected = session.select(0);
    assert_eq!(selected.commit, "q.com");
}

#[test]
fn website_completion_filters_and_appends_only_the_missing_suffix() {
    let mut session = session();
    let frame = session.complete("www.g");
    let choices: Vec<&str> = frame.candidates.iter().map(|candidate| candidate.text.as_str()).collect();
    assert_eq!(choices, ["www.google.com", "www.github.com", "www.gmail.com"]);
    let selected = session.select(0);
    assert_eq!(selected.commit, "oogle.com");
}

#[test]
fn website_completion_requires_a_standalone_prefix_and_no_path() {
    let mut session = session();
    assert!(session.complete("notwww.g").candidates.is_empty());
    assert!(session.complete("www.google.com/path").candidates.is_empty());
}
