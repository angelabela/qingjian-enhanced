//! 带时间衰减的计数：牛顿冷却。选择次数只增不减时，半年前的临时偏好会永久占着候选位；
//! 按「N 天不用就减半」冷却，权重跟着现在的习惯走。粒度是天（unix 天数），落盘带最近使用日，
//! 加载时按它补算衰减，进程重启不重置。

use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

/// 缺省半衰期（天）：90 天不用，计数减半。回放调参可用 `--tune decay-days=` 覆盖，0 为不衰减。
pub const DEFAULT_HALF_LIFE_DAYS: f64 = 90.0;
/// 用户主词频短期半衰期：3 天。
pub const DEFAULT_SHORT_HALF_LIFE_DAYS: f64 = 3.0;
/// 用户主词频长期半衰期：45 天。
pub const DEFAULT_LONG_HALF_LIFE_DAYS: f64 = 45.0;

/// 冷了多少天算是「真的不用了」：落盘时把最近使用日在这一年之外的条目清出。
/// 衰减只影响排序权重，记录本身不清——只选过一两次的词冷却之后还留在文件里，别让用户觉得学过的词被忘掉了。
pub const COLD_DAYS: u64 = 365;

/// 今天是 unix 纪元起第几天。UTC 的「天」与本地时区差半天，对按天冷却没有影响。
pub fn today() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() / 86_400)
}

/// 文件的修改时间换算成 unix 天；读不到（新建的文件）就当今天。
/// 旧格式文件里没有最近使用日，加载时拿它当整份文件的缺省日。
pub fn mtime_day(path: &Path) -> u64 {
    std::fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map_or_else(today, |d| d.as_secs() / 86_400)
}

/// 一条会冷却的计数：值与最近一次使用的日期。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Decay {
    /// 累计次数（只被记录与撤销改动，衰减不写回它）。
    pub count: u32,

    /// 最近一次使用的 unix 天数。
    pub last_used: u64,

    /// 双时间尺度短期 log 分（半衰期默认 3 个活跃日）。
    pub ls: f64,
    /// 双时间尺度长期 log 分（半衰期默认 45 个活跃日）。
    pub ll: f64,
    /// 最近一次有效记录的 Unix 秒时间戳。
    pub tk: u64,
    /// 去重后的累计命中次数。
    pub hits: u32,
    /// 系统种子/系统词标记，个人选择默认 false。
    pub sys: bool,
    /// 永久屏蔽标记。
    pub banned: bool,
}

impl Decay {
    /// 从落盘的 (次数, 天数) 还原。
    pub fn from_parts(count: u32, last_used: u64) -> Self {
        Self { count, last_used, ls: if count == 0 { f64::NEG_INFINITY } else { f64::from(count).ln() }, ll: if count == 0 { f64::NEG_INFINITY } else { f64::from(count).ln() }, tk: last_used.saturating_mul(86_400), hits: count, sys: false, banned: false }
    }

    /// 记 `times` 次，刷新最近使用日。
    pub fn record(&mut self, times: u32, now: u64) {
        self.count = self.count.saturating_add(times);
        self.last_used = now.max(self.last_used);
    }

    /// 退回最多 `times` 次（撤销），返回实际退回的份数；减到零由调用方删条目。
    pub fn unrecord(&mut self, times: u32) -> u32 {
        let removed = times.min(self.count);
        self.count -= removed;
        removed
    }

    /// 以秒为单位记录一次命中；同一词 3 秒内重复触发视为重复事件，不重复学习。
    pub fn record_dual(&mut self, now: u64, short_days: f64, long_days: f64) -> bool {
        if self.banned { return false; }
        if self.hits > 0 && now.saturating_sub(self.tk) < 3 {
            return false;
        }
        // 先把历史 log 分衰减到当前时刻，再 logAdd 新命中；不能让新命中把旧偏好整体刷新。
        let elapsed = now.saturating_sub(self.tk) as f64;
        if short_days > 0.0 && self.ls.is_finite() {
            self.ls -= std::f64::consts::LN_2 * elapsed / (short_days * 86_400.0);
        }
        if long_days > 0.0 && self.ll.is_finite() {
            self.ll -= std::f64::consts::LN_2 * elapsed / (long_days * 86_400.0);
        }
        self.ls = log_add(self.ls, 0.0);
        self.ll = log_add(self.ll, 0.0);
        self.tk = now.max(self.tk);
        self.last_used = self.tk / 86_400;
        self.hits = self.hits.saturating_add(1);
        self.count = self.hits;
        true
    }

    /// 撤销一份刚提交的命中，分别从短期和长期 log 分中减去一份质量。
    pub fn unrecord_dual(&mut self) -> u32 {
        if self.hits == 0 { return 0; }
        self.hits -= 1;
        self.count = self.hits;
        self.ls = log_sub_one(self.ls);
        self.ll = log_sub_one(self.ll);
        1
    }

    /// 按用户方案计算双时间尺度有效值。
    pub fn effective_dual(&self, now: u64, short_days: f64, long_days: f64) -> u32 {
        if self.banned || self.hits == 0 { return 0; }
        let delta = now.saturating_sub(self.tk) as f64;
        let short_lambda = if short_days > 0.0 { std::f64::consts::LN_2 / (short_days * 86_400.0) } else { 0.0 };
        let long_lambda = if long_days > 0.0 { std::f64::consts::LN_2 / (long_days * 86_400.0) } else { 0.0 };
        let short = if short_days > 0.0 { (self.ls - short_lambda * delta).exp() } else { self.ls.exp() };
        let long = if long_days > 0.0 { (self.ll - long_lambda * delta).exp() } else { self.ll.exp() };
        (0.4 * short + 0.6 * long).round().clamp(0.0, f64::from(u32::MAX)) as u32
    }

    /// 恢复新格式字段，旧版 TSV 仍由 `from_parts` 兼容加载。
    pub fn with_dual_fields(mut self, ls: f64, ll: f64, tk: u64, hits: u32, sys: bool, banned: bool) -> Self {
        self.ls = ls;
        self.ll = ll;
        self.tk = tk;
        self.hits = hits;
        self.count = hits;
        self.last_used = tk / 86_400;
        self.sys = sys;
        self.banned = banned;
        self
    }

    /// 有效次数：`count × 2^(−间隔天数 / 半衰期)` 四舍五入。冷却到 0 的条目查询时已不起作用，
    /// 记录仍留着，冷满 [`COLD_DAYS`] 才在落盘时清出。`half_life_days` 非正数视为不衰减。
    pub fn effective(&self, now: u64, half_life_days: f64) -> u32 {
        if half_life_days <= 0.0 {
            return self.count;
        }
        let days = now.saturating_sub(self.last_used) as f64;
        (f64::from(self.count) * (-days / half_life_days).exp2()).round() as u32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn effective_decays_by_half_life_and_floor_is_zero() {
        let entry = Decay::from_parts(8, 100);
        assert_eq!(entry.effective(100, 30.0), 8);
        assert_eq!(entry.effective(130, 30.0), 4);
        assert_eq!(entry.effective(160, 30.0), 2);
        assert_eq!(entry.effective(190, 30.0), 1);
        // 恰好 0.5 按「四舍五入远离零」进位：120 天还是 1，再老 10 天才是 0
        assert_eq!(entry.effective(220, 30.0), 1);
        assert_eq!(entry.effective(230, 30.0), 0);
        // 未来日期（时钟回拨）不放大计数
        assert_eq!(entry.effective(90, 30.0), 8);
    }

    #[test]
    fn zero_half_life_disables_decay() {
        let entry = Decay::from_parts(3, 1);
        assert_eq!(entry.effective(100_000, 0.0), 3);
        assert_eq!(entry.effective(100_000, -1.0), 3);
    }

    #[test]
    fn record_refreshes_last_used_and_unrecord_removes() {
        let mut entry = Decay::from_parts(5, 100);
        entry.record(1, 200);
        assert_eq!(entry.count, 6);
        assert_eq!(entry.last_used, 200);
        // 时钟回拨时不往回改
        entry.record(1, 150);
        assert_eq!(entry.last_used, 200);
        assert_eq!(entry.unrecord(2), 2);
        assert_eq!(entry.count, 5);
        assert_eq!(entry.unrecord(9), 5);
        assert_eq!(entry.count, 0);
    }
}

/// 稳定的 log(exp(a) + exp(b))。
fn log_add(a: f64, b: f64) -> f64 {
    if !a.is_finite() { return b; }
    if !b.is_finite() { return a; }
    let max = a.max(b);
    max + ((a - max).exp() + (b - max).exp()).ln()
}

/// 从 log 分中减去一份权重为 1 的记录。
fn log_sub_one(value: f64) -> f64 {
    if !value.is_finite() || value <= 0.0 { return f64::NEG_INFINITY; }
    value + (-(-value).exp()).ln_1p()
}
