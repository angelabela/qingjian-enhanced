//! 交给壳的常用语整页。

use qingjian_learning::Phrase;
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct PhrasePage {
    /// 分组，「默认」在第一个。
    pub groups: Vec<String>,

    /// 全部常用语，靠前的常用；壳按分组筛。
    pub phrases: Vec<Phrase>,

    /// 本次请求是否真正成功；写入失败或只读内存模式均为 false。
    pub operation_succeeded: bool,
}
