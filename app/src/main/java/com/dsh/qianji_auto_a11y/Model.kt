package com.dsh.qianji_auto_a11y

object Pkg {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val UNIONPAY = "com.unionpay"
    val ALL = setOf(WECHAT, ALIPAY, UNIONPAY)
}

data class NodeMeta(
    val text: String,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val cls: String
) {
    val height: Int get() = bottom - top
}

data class Draft(
    val pkg: String,
    val amount: Double,
    val merchant: String,
    val catename: String,
    val account: String,
    val book: String,
    val type: Int,
    val remark: String,
    val cateChoose: Boolean = false,
    val time: String? = null,
    val paymentMethod: String? = null,
    val coupon: String = "",
    val account2: String = "",
    val fee: String = "",
    /** 本地记录里显示的金额（优惠后的实付），null 表示同 amount */
    val logAmount: Double? = null,
    /** 本地记录里显示的分类文案，null 表示同 catename */
    val logCatename: String? = null,
    /** 本地记录里显示的资产文案，null 表示同 account */
    val logAccount: String? = null
)

data class RuleRow(val id: Long, val keyword: String, val catename: String)
