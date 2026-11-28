/// 笔画预处理 — 对应 Python dataset.py 的 strokes_to_sequence
use candle_core::{Device, Result, Tensor};

/// 将原始笔画坐标转为模型输入特征序列 (B, T, 5)
/// strokes: Vec<Vec<(f32, f32)>> — 每笔的点坐标列表
pub fn strokes_to_tensor(
    strokes: &[Vec<(f32, f32)>],
    device: &Device,
) -> Result<Tensor> {
    // 展平所有点 + 落笔标志
    let mut xs: Vec<f32> = Vec::new();
    let mut ys: Vec<f32> = Vec::new();
    let mut pen_down: Vec<f32> = Vec::new();

    for stroke in strokes {
        for &(x, y) in stroke {
            xs.push(x);
            ys.push(y);
            pen_down.push(1.0);
        }
        if let Some(last) = pen_down.last_mut() {
            *last = 0.0; // 每笔最后一个点为抬笔
        }
    }

    if xs.is_empty() {
        return Tensor::zeros((1, 1, 5), candle_core::DType::F32, device);
    }

    // 归一化到 [0, 1]
    let min_x = xs.iter().cloned().fold(f32::INFINITY, f32::min);
    let max_x = xs.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let min_y = ys.iter().cloned().fold(f32::INFINITY, f32::min);
    let max_y = ys.iter().cloned().fold(f32::NEG_INFINITY, f32::max);

    let range_x = (max_x - min_x).max(1.0);
    let range_y = (max_y - min_y).max(1.0);
    // 使用统一比例缩放并居中，而不是分别拉伸 X/Y。
    // 独立拉伸会把窄字、长横和斜笔画压扁/拉宽，改变模型实际看到的字形结构。
    let scale = range_x.max(range_y).max(1.0);
    let offset_x = (scale - range_x) * 0.5;
    let offset_y = (scale - range_y) * 0.5;

    let n = xs.len();
    let mut features = Vec::with_capacity(n * 5);

    let mut prev_x = xs[0];
    let mut prev_y = ys[0];

    for i in 0..n {
        let x_norm = (xs[i] - min_x + offset_x) / scale;
        let y_norm = (ys[i] - min_y + offset_y) / scale;
        let dx = (xs[i] - prev_x) / scale;
        let dy = (ys[i] - prev_y) / scale;

        features.push(x_norm);
        features.push(y_norm);
        features.push(dx);
        features.push(dy);
        features.push(pen_down[i]);

        prev_x = xs[i];
        prev_y = ys[i];
    }

    Tensor::from_vec(features, (1, n, 5), device)
}

/// 生成 attention mask: (B, T), True = 有效位置
pub fn create_mask(n: usize, device: &Device) -> Result<Tensor> {
    let data: Vec<u8> = vec![1; n];
    Tensor::from_vec(data, (1, n), device)
}
