//! JNI bridge for offline ochwpro stroke recognition.
use ::jni::JNIEnv;
use ::jni::objects::{JObject, JString};
use ::jni::sys::jstring;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize)]
struct StrokeRequest { strokes: Vec<Vec<[f32; 2]>> }

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_inputmethod_core_Native_recognizeHandwriting<'local>(
 mut env: JNIEnv<'local>, _this: JObject<'local>, model_dir: JString<'local>, stroke_json: JString<'local>,
) -> jstring {
 let result = (|| -> anyhow::Result<String> {
  let dir = PathBuf::from(String::from(env.get_string(&model_dir)?));
  let raw = String::from(env.get_string(&stroke_json)?);
  let request: StrokeRequest = serde_json::from_str(&raw)?;
  let strokes: Vec<Vec<(f32,f32)>> = request.strokes.into_iter()
   .map(|s| s.into_iter().map(|p| (p[0],p[1])).collect()).collect();
  let candidates = crate::handwriting::recognize_from_dir(&dir, &strokes, 10)?;
  Ok(serde_json::to_string(&candidates)?)
 })();
 match result {
  Ok(json) => env.new_string(json).map(JString::into_raw).unwrap_or(std::ptr::null_mut()),
  Err(error) => {
   tracing::warn!(%error, "offline handwriting recognition failed");
   std::ptr::null_mut()
  }
 }
}
