package com.dsh.qianji_auto_a11y

/**
 * 从页面里抽出来的原始字段，还没和本地数据（分类/资产/账本）做映射。
 * 接入新 App 时只需要让它产出这个结构，后面的业务逻辑完全复用。
 */
data class Extracted(
    /** 命中的规则 id，便于调试 */
    val ruleId: String,
    val amount: Double,
    val amountText: String,
    val merchant: String,
    val desc: String?,
    val timeText: String,
    val method: String?,
    val pageCategory: String?,
    /** 0 支出 / 1 收入 / 2+ 其他，-1 表示页面没给出线索 */
    val typeHint: Int
)
