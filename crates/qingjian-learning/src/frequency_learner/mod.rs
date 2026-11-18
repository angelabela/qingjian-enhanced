mod decay;
mod demotions;
mod learner_impl;
mod tables;

use std::cell::Cell;
use std::collections::BTreeMap;

use decay::{DEFAULT_HALF_LIFE_DAYS, DEFAULT_LONG_HALF_LIFE_DAYS, DEFAULT_SHORT_HALF_LIFE_DAYS, Decay};
use foldhash::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use qingjian_core::sentence::{Context, SENTENCE_START, UserNgram};
use qingjian_core::storage::{read_text_lossy, write_atomic};
use qingjian_core::{Candidate, Forgotten, Learner};
use qingjian_dictionary::{Dictionary, WordList};

use crate::error::LearningError;

/// 用户词文件名，与词频文件放同一目录；格式与主词库 TSV 相同（`词\t拼音\t词频`）。
const USER_WORDS_FILE: &str = "user-words.tsv";

/// 用户词在小词库里的词频。排序主要靠 weight，这个值只在同 weight 时起作用。
const USER_WORD_FREQUENCY: u32 = 100;

/// 个人 n-gram 文件名，与词频文件同目录：`前词\t后词\t次数`，句首用 `<s>`。
const USER_NGRAM_FILE: &str = "user-ngram.tsv";

/// 按输入串记的选择文件名，与词频文件同目录：`输入串\t词\t次数`。
const USER_CHOICES_FILE: &str = "user-choices.tsv";

/// 候选菜单里降权的词，与词频文件同目录：`输入串\t词\t降了几次`。
const USER_DEMOTED_FILE: &str = "user-demoted.tsv";

/// 一个词在一个输入串下最多降几次。
const MAX_DEMOTION: u32 = 99;

/// 个人英文词表文件名，与词频文件同目录：`词\t次数`（词按最近一次敲的写法存）。
const USER_ENGLISH_FILE: &str = "user-english.tsv";

/// 个人敲错表文件名，与词频文件同目录：`敲的\t要的\t次数`（音节级，接受过的纠正）。
const USER_TYPOS_FILE: &str = "user-typos.tsv";

/// 三张带衰减的表（词频 / 个人英文词 / 敲错表）的最近使用日侧车文件：`词\t天数` 一类。
/// 主表保持旧格式两列 / 三列，旧版本读不到日期列也不会丢行；日期丢了就整份退回文件的修改日。
const USER_DAYS_FILE: &str = "user-days.tsv";
const USER_DUAL_FILE: &str = "user-frequency-dual.tsv";
const USER_BIGRAM_DUAL_FILE: &str = "user-ngram-dual.tsv";
const MAX_USER_UNIGRAMS: usize = 60_000;
const MAX_USER_BIGRAMS: usize = 120_000;
const USER_ENGLISH_DAYS_FILE: &str = "user-english-days.tsv";
const USER_TYPOS_DAYS_FILE: &str = "user-typos-days.tsv";

/// 按输入串记的选择最多存多少条 (输入串, 词)，超过就整体减半（忘掉久远的偏好）。
const MAX_CHOICE_ENTRIES: usize = 50_000;

/// 「回车原样上屏过」在选择表里的记法：词那一列写这个标记（尖括号不可能是候选词）。
const RAW_MARK: &str = "<raw>";

/// 内存中的用户词频表。
///
/// 词频、个人英文词与个人敲错表三张表带时间衰减（`Decay`，牛顿冷却）：记录时刷新最近使用日，
/// 查询时按半衰期折算有效次数。衰减只用在排序权重上，记录本身留着——真要清等冷了一年
/// （`COLD_DAYS`），落盘时才连侧车一起清出。不改「一次误选翻不过静态证据」的封顶设计。
#[derive(Debug)]
pub struct FrequencyLearner {
    /// 词 → 用户选择次数（带最近使用日）。
    counts: HashMap<String, Decay>,

    /// 衰减半衰期（天）；0 为不衰减。缺省 [`DEFAULT_HALF_LIFE_DAYS`]，回放调参可改。
    half_life_days: f64,

    /// 主词频的双时间尺度半衰期。
    short_half_life_days: f64,
    long_half_life_days: f64,

    /// 当前 Unix 秒，读路径使用缓存，避免候选排序时反复取系统时钟。
    now_seconds: Cell<u64>,

    /// 今天的 unix 天数。读路径（`weight` 等）是 `&self`，不能每次查时钟——写路径刷新它，读路径用缓存值，
    /// 粒度是天，进程活得再久也只差一天。
    today: Cell<u64>,

    /// 自上次保存后是否有新记录。
    dirty: bool,

    /// [`Learner::flush`] 时写回的路径；`None` 表示只在内存里学习。
    path: Option<PathBuf>,

    /// 用户词：词 → 全拼（空格分隔）。词库里没有、用户却选过的词（云联想接受的等）。
    words: BTreeMap<String, String>,

    /// 用户词组成的小词库，随 `words` 重建。
    user_dictionary: Option<Dictionary>,

    /// 用户词自上次保存后是否有变化。
    words_dirty: bool,

    /// 个人 n-gram：上屏词序列的转移计数（二元 + 三元）。
    ngram: UserNgram,

    /// 带双时间尺度的个人二元转移（前词, 后词）；UserNgram 是供解码器读取的实时有效计数视图。
    bigrams: HashMap<(String, String), Decay>,

    /// 二元转移自上次保存后是否有变化。
    bigrams_dirty: bool,

    /// 个人 n-gram 自上次保存后是否有变化。
    ngram_dirty: bool,

    /// 输入串 → (词 → 在这个输入串下被选的次数)。读音键与字面键混在同一张表（见 [`Learner::record_choice`]）。
    choices: HashMap<String, HashMap<String, u32>>,

    /// 按输入串记的选择自上次保存后是否有变化。
    choices_dirty: bool,

    /// 个人英文词：小写编码 → (最近一次敲的写法, 带衰减的次数)。回车原样上屏的英文词、选过的英文候选。
    english: BTreeMap<String, (String, Decay)>,

    /// 个人英文词组成的词表，随 `english` 重建。
    english_list: Option<WordList>,

    /// 个人英文词自上次保存后是否有变化。
    english_dirty: bool,

    /// 个人敲错表：敲的那段字母 → (要的音节 → 带衰减的接受次数)。
    typos: HashMap<String, HashMap<String, Decay>>,

    /// 个人敲错表自上次保存后是否有变化。
    typos_dirty: bool,

    /// 输入串 → (词 → 用户在候选菜单里降了它几次)。
    demoted: HashMap<String, HashMap<String, u32>>,

    /// 降权表自上次保存后是否有变化。
    demoted_dirty: bool,
}

impl Default for FrequencyLearner {
    fn default() -> Self {
        Self {
            counts: HashMap::default(),
            half_life_days: DEFAULT_HALF_LIFE_DAYS,
            short_half_life_days: DEFAULT_SHORT_HALF_LIFE_DAYS,
            long_half_life_days: DEFAULT_LONG_HALF_LIFE_DAYS,
            now_seconds: Cell::new(SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())),
            today: Cell::new(decay::today()),
            dirty: false,
            path: None,
            words: BTreeMap::default(),
            user_dictionary: None,
            words_dirty: false,
            ngram: UserNgram::default(),
            bigrams: HashMap::default(),
            bigrams_dirty: false,
            ngram_dirty: false,
            choices: HashMap::default(),
            choices_dirty: false,
            english: BTreeMap::default(),
            english_list: None,
            english_dirty: false,
            typos: HashMap::default(),
            typos_dirty: false,
            demoted: HashMap::default(),
            demoted_dirty: false,
        }
    }
}

impl FrequencyLearner {
    /// 从 `词\t次数` 文本建表（只在内存里学习）。坏行跳过。
    pub fn parse(source: &str) -> Self {
        let mut learner = Self::default();
        learner.load_counts(source, decay::today());
        learner
    }

    /// 从 `词\t次数` 读选择次数（旧格式，两列），最近使用日先记 `fallback_day`（侧车会覆盖），返回跳过的坏行数。
    fn load_counts(&mut self, source: &str, fallback_day: u64) -> usize {
        let mut skipped = 0;
        for line in data_lines(source) {
            let parsed = line.split_once('\t').and_then(|(text, count)| {
                count.trim().parse::<u32>().ok().map(|count| (text, count))
            });
            match parsed {
                Some((text, count)) => {
                    if count > 0 {
                        self.counts
                            .insert(text.to_owned(), Decay::from_parts(count, fallback_day));
                    }
                }
                None => skipped += 1,
            }
        }
        skipped
    }

    /// 从侧车文件 `词\t天数` 读最近使用日，覆盖词频表里的初值。
    fn load_days(&mut self, source: &str) {
        for line in data_lines(source) {
            if let Some((text, day)) = line.split_once('\t')
                && let Ok(day) = day.trim().parse::<u64>()
                && let Some(entry) = self.counts.get_mut(text)
            {
                entry.last_used = day;
                entry.tk = day.saturating_mul(86_400);
            }
        }
    }

    /// 加载双时间尺度字段。不存在此文件时，旧版计数已由 `load_counts` 转换为等价初始分。
    fn load_dual(&mut self, source: &str) -> usize {
        let mut skipped = 0;
        for line in data_lines(source) {
            let fields: Vec<&str> = line.split('\t').collect();
            if let [text, ls, ll, tk, hits, sys, banned] = fields.as_slice() {
                let parsed = (|| Some((
                    ls.parse::<f64>().ok()?,
                    ll.parse::<f64>().ok()?,
                    tk.parse::<u64>().ok()?,
                    hits.parse::<u32>().ok()?,
                    sys.parse::<u8>().ok()? != 0,
                    banned.parse::<u8>().ok()? != 0,
                )))();
                if let Some((ls, ll, tk, hits, sys, banned)) = parsed
                    && !ls.is_nan() && !ll.is_nan() && (hits > 0 || banned)
                {
                    if let Some(entry) = self.counts.get_mut(*text) {
                        *entry = entry.with_dual_fields(ls, ll, tk, hits, sys, banned);
                    } else {
                        self.counts.insert((*text).to_owned(),
                            Decay::from_parts(hits, tk / 86_400).with_dual_fields(ls, ll, tk, hits, sys, banned));
                    }
                } else {
                    skipped += 1;
                }
            } else {
                skipped += 1;
            }
        }
        skipped
    }

    /// 从旧 n-gram TSV 的三列二元行迁移；四列三元行继续保留在 UserNgram 中。
    fn load_legacy_bigrams(&mut self, source: &str, fallback_day: u64) {
        for line in data_lines(source) {
            let fields: Vec<&str> = line.split('\t').collect();
            if let [previous, word, count] = fields.as_slice()
                && let Ok(count) = count.parse::<u32>()
                && count > 0
            {
                self.bigrams.insert(
                    ((*previous).to_owned(), (*word).to_owned()),
                    Decay::from_parts(count, fallback_day),
                );
            }
        }
    }

    /// 新格式二元侧车：前词、后词、ls、ll、tk、hits、sys、banned。
    fn load_dual_bigrams(&mut self, source: &str) -> usize {
        let mut skipped = 0;
        for line in data_lines(source) {
            let fields: Vec<&str> = line.split('\t').collect();
            if let [previous, word, ls, ll, tk, hits, sys, banned] = fields.as_slice() {
                let parsed = (|| Some((
                    ls.parse::<f64>().ok()?, ll.parse::<f64>().ok()?, tk.parse::<u64>().ok()?,
                    hits.parse::<u32>().ok()?, sys.parse::<u8>().ok()? != 0,
                    banned.parse::<u8>().ok()? != 0,
                )))();
                if let Some((ls, ll, tk, hits, sys, banned)) = parsed
                    && !ls.is_nan() && !ll.is_nan() && (hits > 0 || banned)
                {
                    self.bigrams.insert(
                        ((*previous).to_owned(), (*word).to_owned()),
                        Decay::from_parts(hits, tk / 86_400).with_dual_fields(ls, ll, tk, hits, sys, banned),
                    );
                } else {
                    skipped += 1;
                }
            } else {
                skipped += 1;
            }
        }
        skipped
    }

    /// 用二元双时间尺度有效值重建解码器模型，同时保留原有三元上下文统计。
    fn rebuild_ngram(&mut self) {
        let triples = self.ngram.to_tsv();
        let mut source = String::new();
        let now = self.now_seconds.get();
        let mut rows: Vec<_> = self.bigrams.iter().collect();
        rows.sort_by(|a, b| a.0.cmp(b.0));
        for ((previous, word), entry) in rows {
            let count = entry.effective_dual(now, self.short_half_life_days, self.long_half_life_days);
            if count > 0 && !entry.banned {
                source.push_str(previous);
                source.push('\t');
                source.push_str(word);
                source.push('\t');
                source.push_str(&count.to_string());
                source.push('\n');
            }
        }
        for line in triples.lines() {
            if line.split('\t').count() == 4 {
                source.push_str(line);
                source.push('\n');
            }
        }
        self.ngram = UserNgram::parse_lenient(&source).0;
    }

    fn save_dual_bigrams_to(&mut self, path: &Path) -> Result<(), LearningError> {
        let mut rows: Vec<_> = self.bigrams.iter().collect();
        rows.sort_by(|a, b| a.0.cmp(b.0));
        write_atomic(path, |file| {
            writeln!(file, "# 青简双时间尺度二元转移：前词\\t后词\\tls\\tll\\ttk\\thits\\tsys\\tbanned")?;
            for ((previous, word), entry) in &rows {
                writeln!(file, "{previous}\t{word}\t{:.9}\t{:.9}\t{}\t{}\t{}\t{}",
                    entry.ls, entry.ll, entry.tk, entry.hits, entry.sys as u8, entry.banned as u8)?;
            }
            Ok(())
        })?;
        self.bigrams_dirty = false;
        Ok(())
    }

    /// 加载后也执行容量约束；永久屏蔽项不淘汰，其余优先保留有效分高的记录。
    fn enforce_capacity(&mut self) {
        let mut pruned_unigrams = false;
        while self.counts.len() > MAX_USER_UNIGRAMS {
            let weakest = self.counts.iter()
                .filter(|(_, entry)| !entry.banned)
                .min_by_key(|(_, entry)| self.effective_dual(entry))
                .map(|(word, _)| word.clone());
            let Some(word) = weakest else { break; };
            self.counts.remove(&word);
            pruned_unigrams = true;
        }
        let mut pruned_bigrams = false;
        while self.bigrams.len() > MAX_USER_BIGRAMS {
            let weakest = self.bigrams.iter()
                .filter(|(_, entry)| !entry.banned)
                .min_by_key(|(_, entry)| self.effective_dual(entry))
                .map(|(key, _)| key.clone());
            let Some(key) = weakest else { break; };
            self.bigrams.remove(&key);
            pruned_bigrams = true;
        }
        self.dirty |= pruned_unigrams;
        self.bigrams_dirty |= pruned_bigrams;
        self.ngram_dirty |= pruned_bigrams;
    }

    /// 刷新缓存的「今天」。写路径开头调；读路径（`weight` 等，一键要查几万次）用缓存值，不查时钟。
    fn tick(&mut self) {
        self.today.set(decay::today());
        self.now_seconds.set(SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()));
    }

    fn effective_dual(&self, entry: &Decay) -> u32 {
        entry.effective_dual(self.now_seconds.get(), self.short_half_life_days, self.long_half_life_days)
    }

    /// 一条带衰减计数的有效次数。
    fn effective(&self, entry: &Decay) -> u32 {
        entry.effective(self.today.get(), self.half_life_days)
    }

    /// 衰减半衰期（天）；0 为不衰减。回放调参用（`--tune decay-days=`）。
    pub fn set_half_life_days(&mut self, days: f64) {
        self.half_life_days = days;
        self.long_half_life_days = days.max(0.0);
        self.short_half_life_days = if days > 0.0 { days * 3.0 / 45.0 } else { 0.0 };
        self.tick();
    }

    /// 将词加入永久屏蔽表；屏蔽词的个性化权重始终为零。
    pub fn ban_word(&mut self, text: &str) -> bool {
        let now = self.now_seconds.get();
        let entry = self.counts.entry(text.to_owned()).or_insert_with(|| Decay::from_parts(0, now / 86_400));
        if entry.banned { return false; }
        entry.banned = true;
        self.dirty = true;
        true
    }

    /// 解除永久屏蔽。
    pub fn unban_word(&mut self, text: &str) -> bool {
        let Some(entry) = self.counts.get_mut(text) else { return false; };
        if !entry.banned { return false; }
        entry.banned = false;
        let empty = entry.hits == 0;
        if empty { self.counts.remove(text); }
        self.dirty = true;
        true
    }

    pub fn is_banned(&self, text: &str) -> bool {
        self.counts.get(text).is_some_and(|entry| entry.banned)
    }

    /// 从文件加载，之后 [`Learner::flush`] 会写回同一个文件。
    /// 文件不存在时返回空表，而不是报错：首次运行没有用户数据是正常的。
    ///
    /// 各文件按行容错：格式不对的行（崩溃写坏、手改错了）记一条警告跳过，其余照读，下次落盘时就没了；
    /// 编码坏掉的字节按替换字符读进来交给按行解析处理。只有真正的 io 错误（权限、坏盘）才返回 `Err`，
    /// 这时壳该退回只在内存里学习，别拿空表覆盖用户的文件。
    pub fn from_path(path: impl Into<PathBuf>) -> Result<Self, LearningError> {
        let path = path.into();
        let mut learner = Self::default();
        let mtime = decay::mtime_day(&path);
        if let Some(source) = read_text_lossy(&path)? {
            let skipped = learner.load_counts(&source, mtime);
            note_skipped(&path, skipped);
        }
        if let Some(source) = read_text_lossy(Self::days_path(&path).as_path())? {
            learner.load_days(&source);
        }
        let dual_path = Self::dual_path(&path);
        if let Some(source) = read_text_lossy(&dual_path)? {
            let skipped = learner.load_dual(&source);
            note_skipped(&dual_path, skipped);
        }
        let words_path = Self::words_path(&path);
        if let Some(source) = read_text_lossy(&words_path)? {
            let skipped = learner.load_words(&source);
            note_skipped(&words_path, skipped);
        }
        let ngram_path = Self::ngram_path(&path);
        if let Some(source) = read_text_lossy(&ngram_path)? {
            let (ngram, skipped) = UserNgram::parse_lenient(&source);
            learner.ngram = ngram;
            note_skipped(&ngram_path, skipped.len());
        }
        // 旧 n-gram TSV 中的二元计数转换为双时间尺度初值；新侧车存在时再覆盖。
        let legacy_ngram = learner.ngram.to_tsv();
        learner.load_legacy_bigrams(&legacy_ngram, decay::mtime_day(&ngram_path));
        let bigram_dual_path = Self::bigram_dual_path(&path);
        if let Some(source) = read_text_lossy(&bigram_dual_path)? {
            let skipped = learner.load_dual_bigrams(&source);
            note_skipped(&bigram_dual_path, skipped);
        }
        learner.enforce_capacity();
        learner.rebuild_ngram();
        let choices_path = Self::choices_path(&path);
        if let Some(source) = read_text_lossy(&choices_path)? {
            let skipped = learner.load_choices(&source);
            note_skipped(&choices_path, skipped);
        }
        let english_path = Self::english_path(&path);
        let english_mtime = decay::mtime_day(&english_path);
        if let Some(source) = read_text_lossy(&english_path)? {
            let skipped = learner.load_english(&source, english_mtime);
            note_skipped(&english_path, skipped);
        }
        if let Some(source) = read_text_lossy(Self::english_days_path(&path).as_path())? {
            learner.load_english_days(&source);
        }
        let typos_path = Self::typos_path(&path);
        let typos_mtime = decay::mtime_day(&typos_path);
        if let Some(source) = read_text_lossy(&typos_path)? {
            let skipped = learner.load_typos(&source, typos_mtime);
            note_skipped(&typos_path, skipped);
        }
        if let Some(source) = read_text_lossy(Self::typos_days_path(&path).as_path())? {
            learner.load_typos_days(&source);
        }
        let demoted_path = Self::demoted_path(&path);
        if let Some(source) = read_text_lossy(&demoted_path)? {
            let skipped = learner.load_demoted(&source);
            note_skipped(&demoted_path, skipped);
        }
        learner.path = Some(path);
        Ok(learner)
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    /// 词频文件 `frequency_path` 与它同目录的各张表、侧车文件：清空或导出学习数据时按这份清单。
    pub fn files(frequency_path: &Path) -> Vec<PathBuf> {
        vec![
            frequency_path.to_path_buf(),
            Self::days_path(frequency_path),
            Self::dual_path(frequency_path),
            Self::bigram_dual_path(frequency_path),
            Self::words_path(frequency_path),
            Self::ngram_path(frequency_path),
            Self::choices_path(frequency_path),
            Self::english_path(frequency_path),
            Self::english_days_path(frequency_path),
            Self::typos_path(frequency_path),
            Self::typos_days_path(frequency_path),
            Self::demoted_path(frequency_path),
        ]
    }

    /// 词频的最近使用日侧车文件与词频文件同目录。
    pub(super) fn days_path(frequency_path: &Path) -> PathBuf {
        frequency_path.with_file_name(USER_DAYS_FILE)
    }

    pub(super) fn dual_path(frequency_path: &Path) -> PathBuf {
        frequency_path.with_file_name(USER_DUAL_FILE)
    }

    pub(super) fn bigram_dual_path(frequency_path: &Path) -> PathBuf {
        frequency_path.with_file_name(USER_BIGRAM_DUAL_FILE)
    }

    pub fn len(&self) -> usize {
        self.counts.len()
    }

    pub fn is_empty(&self) -> bool {
        self.counts.is_empty()
    }

    pub fn save_to(&mut self, path: impl AsRef<Path>) -> Result<(), LearningError> {
        let now = self.now_seconds.get();
        // 365 天未使用才清理记录；屏蔽词永久保留，短期分下降不会直接删除用户学过的词。
        self.counts.retain(|_, entry| entry.banned || now.saturating_sub(entry.tk) <= decay::COLD_DAYS.saturating_mul(86_400));

        // 先写完整双时间尺度侧车，再写旧版兼容文件；崩溃发生在中间时，新版仍以侧车为准。
        let dual_path = Self::dual_path(path.as_ref());
        write_atomic(&dual_path, |file| {
            writeln!(file, "# 青简双时间尺度词频：词\\tls\\tll\\ttk\\thits\\tsys\\tbanned")?;
            let mut dual_rows: Vec<(&String, &Decay)> = self.counts.iter().collect();
            dual_rows.sort_by(|a, b| a.0.cmp(b.0));
            for (text, entry) in dual_rows {
                writeln!(file, "{text}\t{:.9}\t{:.9}\t{}\t{}\t{}\t{}",
                    entry.ls, entry.ll, entry.tk, entry.hits, entry.sys as u8, entry.banned as u8)?;
            }
            Ok(())
        })?;

        let mut rows: Vec<(&String, &Decay)> = self.counts.iter().collect();
        rows.sort_by(|a, b| {
            b.1.effective_dual(now, self.short_half_life_days, self.long_half_life_days)
                .cmp(&a.1.effective_dual(now, self.short_half_life_days, self.long_half_life_days))
                .then_with(|| a.0.cmp(b.0))
        });
        write_atomic(path.as_ref(), |file| {
            writeln!(file, "# 青简用户词频：词\\t选择次数")?;
            for (text, entry) in rows {
                writeln!(file, "{text}\t{}", entry.hits)?;
            }
            Ok(())
        })?;
        let days_path = Self::days_path(path.as_ref());
        let days: Vec<(&String, u64)> = self.counts.iter().map(|(text, entry)| (text, entry.tk / 86_400)).collect();
        write_atomic(&days_path, |file| {
            writeln!(file, "# 青简用户词频最近使用日：词\\t天数（unix 天）")?;
            for (text, day) in days {
                writeln!(file, "{text}\t{day}")?;
            }
            Ok(())
        })?;
        self.dirty = false;
        Ok(())
    }

    /// 有没有还没落盘的学习数据（任何一张表）。
    pub fn has_unsaved(&self) -> bool {
        self.dirty
            || self.words_dirty
            || self.english_dirty
            || self.ngram_dirty
            || self.bigrams_dirty
            || self.choices_dirty
            || self.typos_dirty
            || self.demoted_dirty
    }
}

/// 数据行：去掉首尾空白、空行与 `#` 注释。
fn data_lines(source: &str) -> impl Iterator<Item = &str> {
    source
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
}

/// 解析 `甲\t乙\t次数` 一行，格式不对返回 `None`。
fn parse_counted_pair(line: &str) -> Option<(&str, &str, u32)> {
    let mut fields = line.split('\t');
    let (Some(first), Some(second), Some(count)) = (fields.next(), fields.next(), fields.next())
    else {
        return None;
    };
    let count = count.trim().parse::<u32>().ok()?;
    Some((first, second, count))
}

/// 加载时跳过了坏行就记一条警告：用户能从日志里知道文件被写坏过，下次落盘会把坏行清掉。
fn note_skipped(path: &Path, skipped: usize) {
    if skipped > 0 {
        tracing::warn!(path = %path.display(), skipped, "学习数据里有格式不对的行，已跳过");
    }
}

#[cfg(test)]
mod tests;
