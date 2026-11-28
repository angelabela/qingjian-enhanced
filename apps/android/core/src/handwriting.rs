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
  let seq_len = strokes.iter().map(Vec::len).sum::<usize>();
  // 触屏采样密度远高于 CASIA 训练数据；超出模型 512 序列长度时按笔画均匀抽样，
  // 每笔保留首尾点，避免整字直接因点数过多而被拒识。
  let bounded;
  let source = if seq_len > 512 {
   let step = seq_len.div_ceil(512).max(1);
   bounded = strokes.iter().map(|stroke| {
    if stroke.len() <= 2 { return stroke.clone(); }
    let mut points: Vec<(f32,f32)> = stroke.iter().step_by(step).copied().collect();
    if let Some(last) = stroke.last().copied() {
     if points.last().copied() != Some(last) { points.push(last); }
    }
    points
   }).collect::<Vec<_>>();
   bounded.as_slice()
  } else { strokes };
  let bounded_len = source.iter().map(Vec::len).sum::<usize>();
  anyhow::ensure!(bounded_len > 0 && bounded_len <= 512, "handwriting point reduction failed: {bounded_len}");
  let input = preprocessing::strokes_to_tensor(source, &Device::Cpu)?;
  let output = self.model.forward(&input, bounded_len)?;
  let probs = candle_nn::ops::softmax(&output, 1)?.squeeze(0)?.to_vec1::<f32>()?;
  let mut ranked: Vec<(usize,f32)> = probs.into_iter().enumerate().collect();
  ranked.sort_by(|a,b| b.1.total_cmp(&a.1));
  Ok(ranked.into_iter().take(top_k.min(12)).map(|(idx,score)| TopK {
   label: self.labels.get(idx).cloned().unwrap_or_else(|| "?".into()), score, class_idx: idx
  }).collect())
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
