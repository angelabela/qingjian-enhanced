package app.qingjian.inputmethod.core.store

/** 常用语整页：分组（「默认」在第一）、全部常用语与本次写入结果。 */
class PhrasePage(
    val groups: List<String>,
    val phrases: List<PhraseItem>,
    val operationSucceeded: Boolean = true,
)
