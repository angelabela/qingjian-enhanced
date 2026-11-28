//! Offline handwriting inference based on ochwpro StrokeTransformer (CPU).
pub mod model;
pub mod preprocessing;
use candle_core::Device;
use serde::Deserialize;
use std::{fs, path::Path, sync::{Mutex, OnceLock}};
#[derive(Clone, Debug, serde::Serialize)]
pub struct TopK { pub label: String, pub score: f32, pub class_idx: usize }
pub struct Inference { model: model::StrokeTransformer, labels: Vec<String> }
impl Inference {
 pub fn load(weights: &[u8], labels: Vec<String>) -> anyhow::Result<Self> {
  anyhow::ensure!(labels.len() == 7356, "ochwpro label count mismatch: {}", labels.len());
  let model = model::load_model(weights, &model::TransformerConfig::default(), &Device::Cpu)?;
  Ok(Self { model, labels })
 }
 pub fn from_dir(dir: &Path) -> anyhow::Result<Self> {
  let weights = fs::read(dir.join("ochw_model_fp16.safetensors"))?;
  let raw = fs::read_to_string(dir.join("char_index.json"))?;
  #[derive(Deserialize)] struct Index { chars: Vec<String> }
  Self::load(&weights, serde_json::from_str::<Index>(&raw)?.chars)
 }
 pub fn predict(&self, strokes: &[Vec<(f32,f32)>], top_k: usize) -> anyhow::Result<Vec<TopK>> {
  anyhow::ensure!(!strokes.is_empty() && strokes.iter().any(|s| !s.is_empty()), "empty handwriting strokes");
  anyhow::ensure!(strokes.len() <= 256, "handwriting stroke count exceeds model budget: {}", strokes.len());
  let seq_len = strokes.iter().map(Vec::len).sum::<usize>();
  // 触屏采样密度远高于 CASIA 训练数据；超出模型 512 序列长度时按笔画均匀抽样，
  // 为每笔额外预留末点预算，避免多笔字在逐笔补尾点后意外超过 512。
  for stroke in strokes {
   anyhow::ensure!(stroke.iter().all(|(x, y)| x.is_finite() && y.is_finite()),
    "handwriting stroke contains non-finite coordinates");
  }
  // 触屏轨迹通常在弯曲处采样密集、直线处采样稀疏。按固定索引抽点会丢失
  // 短弯钩和转折；超过 Transformer 的 512 点预算时，改用按弧长等距重采样。
  let bounded = if seq_len > 512 { resample_strokes(strokes, 512) } else { strokes.to_vec() };
  let bounded_len = bounded.iter().map(Vec::len).sum::<usize>();
  anyhow::ensure!(bounded_len > 0 && bounded_len <= 512, "handwriting point reduction failed: {bounded_len}");
  let input = preprocessing::strokes_to_tensor(&bounded, &Device::Cpu)?;
  let output = self.model.forward(&input, bounded_len)?;
  let probs = candle_nn::ops::softmax(&output, 1)?.squeeze(0)?.to_vec1::<f32>()?;
  let mut ranked: Vec<(usize,f32)> = probs.into_iter().enumerate().collect();
  ranked.sort_by(|a,b| b.1.total_cmp(&a.1));
  Ok(ranked.into_iter().take(top_k.min(12)).map(|(idx,score)| TopK {
   label: self.labels.get(idx).cloned().unwrap_or_else(|| "?".into()), score, class_idx: idx
  }).collect())
 }
}
/// 按笔画弧长分配预算并等距插值，保留每笔起点/终点与笔画边界。
/// 每笔至少保留一个点；可用预算优先按实际路径长度分配，避免长笔画的曲线被过度抽稀。
fn resample_strokes(strokes: &[Vec<(f32, f32)>], budget: usize) -> Vec<Vec<(f32, f32)>> {
 let mut result = vec![Vec::new(); strokes.len()];
 if strokes.is_empty() || budget == 0 { return result; }
 let lengths: Vec<f32> = strokes.iter().map(|stroke| {
  stroke.windows(2).map(|pair| {
   let dx = pair[1].0 - pair[0].0;
   let dy = pair[1].1 - pair[0].1;
   (dx * dx + dy * dy).sqrt()
  }).sum::<f32>()
 }).collect();
 let minimums: Vec<usize> = strokes.iter().map(|s| s.len().min(2)).collect();
 let minimum_total: usize = minimums.iter().sum();
 if minimum_total >= budget {
  // 极端多笔情况下，每笔至少留首尾；调用方已限制最多 256 笔。
  for (index, stroke) in strokes.iter().enumerate() {
   result[index] = if stroke.len() <= 2 { stroke.clone() } else { vec![stroke[0], *stroke.last().unwrap()] };
  }
  return result;
 }
 let remaining = budget - minimum_total;
 let total_length: f32 = lengths.iter().sum();
 let mut quotas = minimums.clone();
 if total_length > 0.0 {
  let mut fractions = Vec::with_capacity(strokes.len());
  let mut assigned = 0usize;
  for i in 0..strokes.len() {
   let raw = remaining as f32 * lengths[i] / total_length;
   let extra = (raw.floor() as usize).min(strokes[i].len().saturating_sub(quotas[i]));
   quotas[i] += extra;
   assigned += extra;
   fractions.push((i, raw.fract()));
  }
  fractions.sort_by(|a, b| b.1.total_cmp(&a.1));
  for (i, _) in fractions {
   if assigned >= remaining { break; }
   if quotas[i] < strokes[i].len() {
    quotas[i] += 1;
    assigned += 1;
   }
  }
 }
 for (index, stroke) in strokes.iter().enumerate() {
  let target = quotas[index].min(stroke.len());
  if target >= stroke.len() {
   result[index] = stroke.clone();
   continue;
  }
  if target <= 1 {
   result[index] = vec![stroke[0]];
   continue;
  }
  let mut cumulative = Vec::with_capacity(stroke.len());
  cumulative.push(0.0f32);
  for pair in stroke.windows(2) {
   let dx = pair[1].0 - pair[0].0;
   let dy = pair[1].1 - pair[0].1;
   cumulative.push(cumulative.last().copied().unwrap_or(0.0) + (dx * dx + dy * dy).sqrt());
  }
  let total = *cumulative.last().unwrap_or(&0.0);
  if total <= f32::EPSILON {
   result[index] = vec![stroke[0]; target];
   continue;
  }
  let mut sampled = Vec::with_capacity(target);
  let mut segment = 0usize;
  for point_index in 0..target {
   let distance = total * point_index as f32 / (target - 1) as f32;
   while segment + 1 < cumulative.len() - 1 && cumulative[segment + 1] < distance {
    segment += 1;
   }
   let start = cumulative[segment];
   let end = cumulative[segment + 1];
   let ratio = if end > start { (distance - start) / (end - start) } else { 0.0 };
   let a = stroke[segment];
   let b = stroke[segment + 1];
   sampled.push((a.0 + (b.0 - a.0) * ratio, a.1 + (b.1 - a.1) * ratio));
  }
  result[index] = sampled;
 }
 result
}

#[cfg(test)]
mod handwriting_tests {
 use super::resample_strokes;

 #[test]
 fn resampling_preserves_stroke_endpoints_and_budget() {
  let strokes = vec![
   (0..800).map(|i| (i as f32, (i as f32 / 17.0).sin() * 20.0)).collect(),
   vec![(10.0, 10.0), (20.0, 20.0)],
  ];
  let sampled = resample_strokes(&strokes, 512);
  assert!(sampled.iter().map(Vec::len).sum::<usize>() <= 512);
  assert_eq!(sampled[0].first(), strokes[0].first());
  assert_eq!(sampled[0].last(), strokes[0].last());
  assert_eq!(sampled[1], strokes[1]);
 }

 #[test]
 fn resampling_keeps_short_strokes() {
  let strokes = vec![vec![(1.0, 2.0)], vec![(3.0, 4.0), (5.0, 6.0)]];
  assert_eq!(resample_strokes(&strokes, 512), strokes);
 }
}

static MODEL: OnceLock<Mutex<Option<Inference>>> = OnceLock::new();
pub fn recognize_from_dir(dir: &Path, strokes: &[Vec<(f32,f32)>], top_k: usize) -> anyhow::Result<Vec<TopK>> {
 let cell = MODEL.get_or_init(|| Mutex::new(None));
 let mut guard = cell.lock().map_err(|_| anyhow::anyhow!("handwriting model lock poisoned"))?;
 if guard.is_none() { *guard = Some(Inference::from_dir(dir)?); }
 guard.as_ref().expect("initialized").predict(strokes, top_k)
}

/// 多字叠写识别：对连续笔画做动态规划切分，再组合单字模型候选。
/// 限制笔画数与每字最大笔画数，避免低端设备上出现无界 CPU 推理。
pub fn recognize_words_from_dir(dir: &Path, strokes: &[Vec<(f32,f32)>]) -> anyhow::Result<Vec<TopK>> {
 let n = strokes.len();
 anyhow::ensure!(n > 0 && n <= 24, "word handwriting stroke count out of range: {n}");
 let cell = MODEL.get_or_init(|| Mutex::new(None));
 let mut guard = cell.lock().map_err(|_| anyhow::anyhow!("handwriting model lock poisoned"))?;
 if guard.is_none() { *guard = Some(Inference::from_dir(dir)?); }
 let model = guard.as_ref().expect("initialized");
 let mut dp = vec![f32::NEG_INFINITY; n + 1];
 let mut previous = vec![0usize; n + 1];
 let mut cache: Vec<Vec<Option<Vec<TopK>>>> = vec![vec![None; n + 1]; n + 1];
 dp[0] = 0.0;
 for end in 1..=n {
  let start_min = end.saturating_sub(12);
  for start in start_min..end {
   let candidates = model.predict(&strokes[start..end], 3)?;
   let best = candidates.first().map(|c| c.score.max(1e-12).ln()).unwrap_or(-30.0);
   cache[start][end] = Some(candidates);
   let total = dp[start] + best + if start == 0 { 0.0 } else { -0.3 };
   if total > dp[end] { dp[end] = total; previous[end] = start; }
  }
 }
 let mut ranges = Vec::new();
 let mut cursor = n;
 while cursor > 0 {
  let start = previous[cursor];
  anyhow::ensure!(start < cursor, "invalid handwriting segmentation");
  ranges.push((start, cursor));
  cursor = start;
 }
 ranges.reverse();
 let mut best_text = String::new();
 let mut parts: Vec<Vec<TopK>> = Vec::new();
 for (start, end) in &ranges {
  let candidates = cache[*start][*end].clone().unwrap_or_default();
  if let Some(best) = candidates.first() { best_text.push_str(&best.label); }
  parts.push(candidates);
 }
 let mut result = vec![TopK { label: best_text.clone(), score: dp[n].exp(), class_idx: 0 }];
 // 给每个分段尝试第二候选，生成少量可纠错的整词变体。
 for index in 0..parts.len() {
  if result.len() >= 10 { break; }
  if parts[index].len() < 2 { continue; }
  let mut text = String::new();
  for (part_index, candidates) in parts.iter().enumerate() {
   if let Some(candidate) = candidates.get(if part_index == index { 1 } else { 0 }) {
    text.push_str(&candidate.label);
   }
  }
  if !text.is_empty() && !result.iter().any(|item| item.label == text) {
   result.push(TopK { label: text, score: parts[index][1].score, class_idx: 0 });
  }
 }
 Ok(result)
}
