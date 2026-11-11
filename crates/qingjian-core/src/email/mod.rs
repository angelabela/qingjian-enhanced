//! 邮箱后缀补全：光标前是「用户名@」（可带敲了一半的域名，如 `abc@q`）时，列出常见邮箱域名，
//! 点一个补上剩下的部分。

mod completion;

pub use self::completion::EmailCompletion;

/// 常见邮箱域名，按国内用户的常用程度排。
const DOMAINS: &[&str] = &[
    "qq.com",
    "163.com",
    "126.com",
    "gmail.com",
    "outlook.com",
    "foxmail.com",
    "hotmail.com",
    "icloud.com",
    "sina.com",
    "139.com",
    "yeah.net",
    "sohu.com",
];

/// 用户名最多看多长：再长就不像在敲邮箱了，也不用往前扫整段上文。
const MAX_LOCAL_LEN: usize = 64;

/// 光标前的文字 `before` 以「用户名@域名前缀」结尾时，给出前缀对得上的常见域名；不是在敲邮箱、
/// 或域名已经敲完整了返回空。域名前缀不分大小写。
pub fn email_completions(before: &str) -> Vec<EmailCompletion> {
    let Some((local, typed)) = before.rsplit_once('@') else {
        return Vec::new();
    };
    if !typed.chars().all(is_domain_char) || !is_local_tail(local) {
        return Vec::new();
    }
    let typed = typed.to_ascii_lowercase();
    DOMAINS
        .iter()
        .filter(|domain| domain.len() > typed.len() && domain.starts_with(&typed))
        .map(|&domain| EmailCompletion {
            domain,
            insert: domain[typed.len()..].to_owned(),
        })
        .collect()
}

fn is_domain_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '.' || c == '-'
}

fn is_local_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '+' | '%')
}

/// `@` 前面紧挨着的是不是一个用户名：至少一个字母或数字，往前一直是用户名能用的字符，
/// 直到开头或别的字（空格、汉字、标点）为止；`a@b@` 这种第二个 `@` 不算。
fn is_local_tail(local: &str) -> bool {
    let name: Vec<char> = local
        .chars()
        .rev()
        .take_while(|&c| is_local_char(c))
        .take(MAX_LOCAL_LEN + 1)
        .collect();
    let starts_cleanly = !local[..local.len() - name.len()].ends_with('@');
    !name.is_empty()
        && name.len() <= MAX_LOCAL_LEN
        && name.iter().any(char::is_ascii_alphanumeric)
        && starts_cleanly
}

#[cfg(test)]
mod tests {
    use super::*;

    fn domains(before: &str) -> Vec<&'static str> {
        email_completions(before)
            .into_iter()
            .map(|completion| completion.domain)
            .collect()
    }

    #[test]
    fn lists_all_domains_right_after_at() {
        let all = email_completions("abc@");
        assert_eq!(all.len(), DOMAINS.len());
        assert_eq!(all[0].domain, "qq.com");
        assert_eq!(all[0].insert, "qq.com");
    }

    #[test]
    fn filters_by_typed_prefix_and_inserts_the_rest() {
        assert_eq!(
            domains("我的邮箱是 abc@1"),
            ["163.com", "126.com", "139.com"]
        );
        let hot = email_completions("x.y_z@HO");
        assert_eq!(hot[0].domain, "hotmail.com");
        assert_eq!(hot[0].insert, "tmail.com");
    }

    #[test]
    fn local_part_can_follow_han_without_space() {
        assert_eq!(domains("邮箱abc@q"), ["qq.com"]);
    }

    #[test]
    fn nothing_when_not_typing_an_email() {
        assert!(domains("@").is_empty());
        assert!(domains("你好@").is_empty());
        assert!(domains("abc@qq.com").is_empty());
        assert!(domains("abc@q q").is_empty());
        assert!(domains("abc@zzz").is_empty());
        assert!(domains("a@b@").is_empty());
        assert!(domains("...@").is_empty());
        assert!(domains("").is_empty());
    }
}


/// 一条网址主机名补全。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrlCompletion {
    pub domain: String,
    pub insert: String,
}

/// 常见网址主机名（不含 www.，按常用程度排列）。
const URL_HOSTS: &[&str] = &[
    "baidu.com", "google.com", "bing.com", "github.com",
    "youtube.com", "wikipedia.org", "qq.com", "163.com",
    "taobao.com", "jd.com", "bilibili.com", "zhihu.com",
    "douyin.com", "xiaohongshu.com", "microsoft.com",
    "apple.com", "amazon.com", "cloudflare.com",
];

/// 中英文输入状态下都可用：根据光标前最后一个网址片段补全主机名。
/// 例如 www.b → www.baidu.com、https://g → https://google.com、github.c → github.com。
pub fn url_completions(before: &str) -> Vec<UrlCompletion> {
    if before.chars().last().is_some_and(char::is_whitespace) {
        return Vec::new();
    }
    let token = before.split_whitespace().last().unwrap_or("");
    let (scheme, host) = if let Some(rest) = token.strip_prefix("https://") {
        ("https://", rest)
    } else if let Some(rest) = token.strip_prefix("http://") {
        ("http://", rest)
    } else {
        ("", token)
    };
    let www = host.starts_with("www.");
    let typed = host.strip_prefix("www.").unwrap_or(host);
    if typed.is_empty()
        && !www
        && scheme.is_empty()
        || host.contains('/')
        || !host.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-'))
        || !(www || scheme != "" || host.contains('.'))
    {
        return Vec::new();
    }
    let typed = typed.to_ascii_lowercase();
    URL_HOSTS.iter()
        .filter(|candidate| candidate.len() > typed.len() && candidate.starts_with(&typed))
        .map(|&candidate| UrlCompletion {
            domain: format!("{scheme}{}{candidate}", if www { "www." } else { "" }),
            insert: candidate[typed.len()..].to_owned(),
        })
        .collect()
}

#[cfg(test)]
mod url_tests {
    use super::*;

    #[test]
    fn completes_www_https_and_partial_domains() {
        let www = url_completions("www.b");
        assert_eq!(www[0].domain, "www.baidu.com");
        assert_eq!(www[0].insert, "aidu.com");

        let https = url_completions("https://g");
        assert!(https.iter().any(|item| item.domain == "https://google.com" && item.insert == "oogle.com"));

        let partial = url_completions("请访问 github.c");
        assert!(partial.iter().any(|item| item.domain == "github.com" && item.insert == "om"));
    }

    #[test]
    fn does_not_suggest_inside_a_path_or_for_plain_words() {
        assert!(url_completions("hello world").is_empty());
        assert!(url_completions("https://github.com/user").is_empty());
        assert!(url_completions("").is_empty());
        assert!(url_completions("刚才说过 ").is_empty());
        assert!(!url_completions("https://").is_empty());
    }
}
