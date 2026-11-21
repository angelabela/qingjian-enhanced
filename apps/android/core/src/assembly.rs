//! 装配 Engine：装的东西与 Linux Server 的 `assembly` 一致；Android 用同一份含章模型做整句生成与上下文候选重排。
//!
//! `data_dir` 是壳从 APK assets 解出来的随包数据目录（`.qj` 要 mmap，必须是真文件），
//! `user_dir` 是应用私有目录下的学习数据目录。

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use qingjian_core::{CharTable, ChoiceModel, EmojiTable, Engine, Language, Translator};
use qingjian_dictionary::{Dictionary, WordList};
use qingjian_learning::{FrequencyLearner, PhraseBook, UsageStats, VocabularyBook};
use qingjian_lm::{BigramModel, MixedModel, TrigramModel, TRIGRAM_WEIGHT};
use qingjian_neural::{CharScorer, P2cScorer};
use qingjian_neural::zhiyi::ChoiceScorer;
use qingjian_translate::{Glossary, LayeredTranslator, PersonalGlossary};

use crate::error::AndroidError;


/// 知意与含章的候选协同模型。知意的拼音单元与含章字级 LM 的输入编码不同，
/// 因此不把同一组 typed token 错误地传给两个模型；含章评分本身只依赖 context + candidate。
struct HybridChoiceScorer {
    zhiyi: Arc<ChoiceScorer>,
    hanzhang: Arc<CharScorer>,
}

impl ChoiceModel for HybridChoiceScorer {
    fn align(&self, keys: &str, syllables: &[String]) -> Option<Vec<u32>> {
        self.zhiyi.align(keys, syllables)
    }

    fn letter_units(&self, keys: &str) -> Vec<u32> {
        self.zhiyi.letter_units(keys)
    }

    fn score(&self, context: &str, typed: &[u32], texts: &[&str]) -> Vec<f32> {
        if texts.is_empty() {
            return Vec::new();
        }
        let zhiyi = self.zhiyi.score(context, typed, texts).ok()
            .filter(|scores| scores.len() == texts.len());
        let hanzhang = self.hanzhang.score(context, texts).ok()
            .filter(|scores| scores.len() == texts.len());
        match (zhiyi, hanzhang) {
            (Some(z), Some(h)) => {
                let z = standardize_scores(&z);
                let h: Vec<f32> = h.into_iter().map(|v| v as f32).collect();
                let h = standardize_scores(&h);
                z.iter().zip(h).map(|(a, b)| 0.72 * a + 0.28 * b).collect()
            }
            (Some(z), None) => z,
            (None, Some(h)) => h.into_iter().map(|v| v as f32).collect(),
            (None, None) => Vec::new(),
        }
    }

    fn compose(&self, context: &str, syllables: &[(&str, bool)]) -> Option<CharTable> {
        match self.zhiyi.compose(context, syllables) {
            Ok(Some(rows)) => Some(CharTable::new(self.zhiyi.char_index(), rows)),
            Ok(None) => None,
            Err(error) => {
                tracing::warn!(%error, "知意按音节出字失败，本次回退词图");
                None
            }
        }
    }
}

/// 候选页内 z-score，去除不同模型 logit 尺度差异；零方差时保留中性分。
fn standardize_scores(scores: &[f32]) -> Vec<f32> {
    if scores.is_empty() {
        return Vec::new();
    }
    let mean = scores.iter().copied().sum::<f32>() / scores.len() as f32;
    let variance = scores.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / scores.len() as f32;
    let std = variance.sqrt();
    if !std.is_finite() || std < 1e-5 {
        return vec![0.0; scores.len()];
    }
    scores.iter().map(|v| ((v - mean) / std).clamp(-3.0, 3.0)).collect()
}

/// Android 端补充的业务短语；独立于跨平台产品数据包，确保常见简拼可命中。
pub(crate) fn builtin_android_dictionary() -> Dictionary {
    Dictionary::parse(
        "已回访\tyi hui fang\t3000
",
    )
    .expect("内置 Android 词条格式固定")
}

pub fn assemble(data_dir: &Path, user_dir: &Path) -> Result<Engine, AndroidError> {
    let started = Instant::now();
    let dictionary = Dictionary::from_path(packed_or_tsv(data_dir, "dict"))?;
    let learner = load_learner(user_dir);
    tracing::info!(
        entries = dictionary.len(),
        learned = learner.len(),
        dictionary_ms = started.elapsed().as_millis(),
        "词库与学习数据已加载"
    );
    let mut engine = Engine::new(dictionary)
        .with_learner(Box::new(learner))
        .with_usage_meter(Box::new(UsageStats::open(user_dir)))
        .with_vocabulary_tracker(Box::new(VocabularyBook::open(
            user_dir.join("user-vocab.tsv"),
        )));
    engine.set_extra_dictionaries(vec![builtin_android_dictionary()]);
    // 学习语言的释义表不在这里装：壳接着交设置（`Session::set_options`），按那里的学习语言装
    let english_glossary = data_dir.join("glossary-zh.qj");
    if english_glossary.is_file() {
        match Glossary::from_path(Language::Chinese, &english_glossary) {
            Ok(glossary) => engine = engine.with_english_translator(Box::new(glossary)),
            Err(error) => tracing::warn!(%error, "英→中释义表加载失败"),
        }
    }
    let english = data_dir.join("english.tsv");
    if english.is_file() {
        match WordList::from_path(&english) {
            Ok(words) => engine = engine.with_english(words),
            Err(error) => tracing::warn!(%error, "英文词表加载失败"),
        }
    }
    if let Some(table) = load_emoji(data_dir) {
        engine = engine.with_emoji(table);
    }
    // 可选知意三元模型：不随 APK 打包，避免额外增加约 79 MB；
    // 用户导入后放在应用用户数据目录 models/zhiyi-ngram/zh.ngram。
    // 三元模型同时用于整句词图（与本地 bigram 按 0.5 插值）和整句候选重排；
    // 缺失或损坏时完全回退到原有 bigram，不影响基础输入。
    let bigram_path = data_dir.join("lm.qj");
    let trigram_path = user_dir.join("models/zhiyi-ngram/zh.ngram");
    let bigram = if bigram_path.is_file() {
        match BigramModel::from_path(&bigram_path) {
            Ok(model) => {
                tracing::info!("二元语言模型已加载");
                Some(model)
            }
            Err(error) => {
                tracing::warn!(%error, "二元语言模型加载失败");
                None
            }
        }
    } else {
        None
    };

    let trigram_enabled = trigram_path.is_file() && trigram_path.with_file_name(".enabled").is_file();
    if trigram_enabled {
        let started = Instant::now();
        match TrigramModel::open(&trigram_path) {
            Ok(phrase_model) => {
                // PhraseModel 为候选整句重排提供完整上下文分；单独 mmap 同一文件，
                // 另一个映射用于词图 LanguageModel，避免复制 79 MB 模型数据。
                engine.set_phrase_model(Some(Arc::new(phrase_model)));
                match TrigramModel::open(&trigram_path) {
                    Ok(trigram_model) => {
                        engine = match bigram {
                            Some(bigram_model) => engine.with_language_model(Box::new(
                                MixedModel::new(
                                    Box::new(trigram_model),
                                    Box::new(bigram_model),
                                    TRIGRAM_WEIGHT,
                                ),
                            )),
                            None => engine.with_language_model(Box::new(trigram_model)),
                        };
                        tracing::info!(
                            load_ms = started.elapsed().as_millis(),
                            trigram_weight = TRIGRAM_WEIGHT,
                            "可选三元语言模型已加载：整句词图与候选重排启用"
                        );
                    }
                    Err(error) => {
                        tracing::warn!(%error, "三元模型词图映射失败，关闭三元重排并保留二元模型");
                        engine.set_phrase_model(None);
                        if let Some(bigram_model) = bigram {
                            engine = engine.with_language_model(Box::new(bigram_model));
                        }
                    }
                }
            }
            Err(error) => {
                tracing::warn!(%error, "三元模型不可用，回退二元模型");
                if let Some(bigram_model) = bigram {
                    engine = engine.with_language_model(Box::new(bigram_model));
                }
            }
        }
    } else if let Some(bigram_model) = bigram {
        engine = engine.with_language_model(Box::new(bigram_model));
        tracing::info!("语言模型已加载：使用随包二元模型");
    } else {
        tracing::warn!("没有可用的静态语言模型，使用词频整句");
    }
    // 含章·通变：本地 P2C 模型按拼音生成整句候选；模型缺失或损坏时保留词图 + bigram 回退。
    let neural_model = data_dir.join("hanzhang-tongbian-small.qjm");
    let mut hanzhang_choice: Option<Arc<CharScorer>> = None;\n    if neural_model.is_file() {
        let started = Instant::now();
        match CharScorer::load(&neural_model) {
            Ok(scorer) => {
                let scorer = Arc::new(scorer);
                match P2cScorer::new_shared(Arc::clone(&scorer)) {
                    Some(p2c) => {
                        tracing::info!(
                            load_ms = started.elapsed().as_millis(),
                            "本地整句生成与候选上下文重排模型已加载"
                        );
                        hanzhang_choice = Some(Arc::clone(&scorer));
                        engine = engine.with_sentence_scorer(Box::new(p2c), None, None, None);
                    }
                    None => tracing::warn!("本地模型缺少 P2C 分隔符，保留词图 + bigram"),
                }
            }
            Err(error) => tracing::warn!(%error, "本地整句模型加载失败，保留词图 + bigram"),
        }
    }
    // 候选协同评分：知意负责拼音条件对齐与主语义分，含章补充上下文语言分；
    // 归一化后融合，避免两个模型原始 logit 尺度不同导致某一方完全压制另一方。
    // 同一个知意实例还保留 compose 出字能力；含章 P2C 仍负责整句生成。
    let zhiyi_dir = user_dir.join("models/zhiyi");
    if zhiyi_dir.is_dir() {
        let started = Instant::now();
        match ChoiceScorer::load(&zhiyi_dir).and_then(|scorer| {
            let typed = scorer.letter_units("ni");
            scorer.score("", &typed, &["你", "泥"])?;
            Ok(scorer)
        }) {
            Ok(zhiyi) => {
                let zhiyi = Arc::new(zhiyi);
                if let Some(hanzhang) = hanzhang_choice.clone() {
                    engine.set_choice_model(Some(Arc::new(HybridChoiceScorer {
                        zhiyi: Arc::clone(&zhiyi),
                        hanzhang,
                    })));
                    tracing::info!("知意 + 含章候选融合评分已启用");
                } else {
                    engine.set_choice_model(Some(zhiyi));
                    tracing::info!("知意候选评分已启用（含章评分模型不可用）");
                }
                tracing::info!(path = %zhiyi_dir.display(), load_ms = started.elapsed().as_millis(), "知意 Transformer 模型已加载并预热");
            }
            Err(error) => tracing::warn!(%error, path = %zhiyi_dir.display(), "知意 Transformer 模型加载失败，保留含章候选评分"),
        }
    } else if let Some(hanzhang) = hanzhang_choice {
        engine.set_choice_model(Some(hanzhang));
        tracing::info!("含章候选评分已启用（未导入知意模型）");
    } else {
        tracing::info!("未启用额外候选评分模型，使用词图与语言模型排序");
    }

    load_phrases(&mut engine, user_dir);
    Ok(engine)
}

/// 「青简翻译」（系统文字选择菜单）用的轻量 Engine：只为 `request_translation` 定方向（汉字为主译成学习语言、外文译成中文），
/// 所以只挂主词库（mmap，几毫秒）与学习语言的释义表，不装语言模型、英文词表、学习数据。`language` 为 `None`（不显示译文）时译成英语，同 PC。
pub fn assemble_translation(
    data_dir: &Path,
    user_dir: &Path,
    language: Option<Language>,
) -> Result<Engine, AndroidError> {
    let mut engine = Engine::new(Dictionary::from_path(packed_or_tsv(data_dir, "dict"))?);
    if let Some(translator) =
        language.and_then(|language| load_translator(data_dir, user_dir, language))
    {
        engine.set_translator(translator);
    }
    Ok(engine)
}

/// 带输入码的常用语挂成自定义短语（打这串码出这条）；常用语改了之后会话也走这里（[`crate::Session::reload_phrases`]）。
pub(crate) fn load_phrases(engine: &mut Engine, user_dir: &Path) {
    if let Err(error) = engine.set_custom_phrases(PhraseBook::open(user_dir).custom_phrases()) {
        tracing::warn!(%error, "常用语的输入码挂不上");
    }
}

/// 一门学习语言的释义：随包表叠上个人释义表（`user-glossary-<语言>.tsv`）。没有这门语言的表或装不上返回 `None`。
pub(crate) fn load_translator(
    data_dir: &Path,
    user_dir: &Path,
    language: Language,
) -> Option<Box<dyn Translator>> {
    let path = packed_or_tsv(data_dir, &format!("glossary-{}", language.code()));
    if !path.is_file() {
        tracing::warn!(language = language.code(), "没有这门语言的释义表");
        return None;
    }
    match Glossary::from_path(language, &path) {
        Ok(bundled) => {
            let personal = PersonalGlossary::open(
                language,
                user_dir.join(format!("user-glossary-{}.tsv", language.code())),
            );
            Some(Box::new(LayeredTranslator::new(bundled, personal)))
        }
        Err(error) => {
            tracing::warn!(%error, language = language.code(), "释义表加载失败");
            None
        }
    }
}

/// `.qj` 优先，没有就用同名 TSV（开发时随包的是 assets/sample/ 样例）。
fn packed_or_tsv(data_dir: &Path, stem: &str) -> PathBuf {
    let packed = data_dir.join(format!("{stem}.qj"));
    if packed.is_file() {
        packed
    } else {
        data_dir.join(format!("{stem}.tsv"))
    }
}

/// 读不了就退回只在内存里学，不拿空表覆盖用户文件。
fn load_learner(user_dir: &Path) -> FrequencyLearner {
    let path = user_dir.join("user.tsv");
    match FrequencyLearner::from_path(&path) {
        Ok(learner) => learner,
        Err(error) => {
            tracing::error!(path = %path.display(), %error, "学习数据读取失败，本次只在内存里学习");
            FrequencyLearner::default()
        }
    }
}

/// 几张 emoji 表合成一张；坏的跳过。
fn load_emoji(data_dir: &Path) -> Option<EmojiTable> {
    let mut merged: Option<EmojiTable> = None;
    for name in ["emoji-zh.tsv", "emoji-en.tsv"] {
        let path = data_dir.join(name);
        if !path.is_file() {
            continue;
        }
        match EmojiTable::from_path(&path) {
            Ok(table) => match &mut merged {
                Some(all) => all.merge(table),
                None => merged = Some(table),
            },
            Err(error) => tracing::warn!(%error, path = %path.display(), "emoji 表加载失败，跳过"),
        }
    }
    merged
}
