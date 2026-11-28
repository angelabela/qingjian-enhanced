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
  anyhow::ensure!(seq_len <= 512, "too many handwriting points: {seq_len}");
  let input = preprocessing::strokes_to_tensor(strokes, &Device::Cpu)?;
  let output = self.model.forward(&input, seq_len)?;
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
