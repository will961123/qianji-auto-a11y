package com.dsh.qianji_auto_a11y

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 记账记录条目的详情：从底部弹出的半屏面板。
 * 列表里分类/商户常被截断，点一下在这里看全。
 */
object EntrySheet {

    fun show(
        ctx: Context,
        e: Store.DayEntry,
        onResend: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(ctx)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
            setBackgroundResource(R.drawable.bg_sheet)
        }

        // 拖动指示条
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bg_handle)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        })

        // 金额 + 类型
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "¥${e.amount}"
            textSize = 28f
            setTextColor(Common.amountColor(e.type))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 类型徽章：和列表里保持同一套样式
        header.addView(TextView(ctx).apply {
            textSize = 12f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            Common.applyTypeBadge(this, e.type)
        })
        root.addView(header)

        // 商户
        root.addView(TextView(ctx).apply {
            text = e.merchant.ifBlank { "未知商户" }
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, dp(6), 0, dp(14))
        })

        val divider = View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        root.addView(divider)

        fun row(label: String, value: String) {
            if (value.isBlank()) return
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(9), 0, dp(9))
            }
            line.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            line.addView(TextView(ctx).apply {
                text = value
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            root.addView(line)
        }

        if (e.coupon.isNotBlank()) {
            val net = e.amount.toDoubleOrNull()
            val discount = e.coupon.toDoubleOrNull()
            val original = if (net != null && discount != null) net + discount else null
            row(
                "优惠",
                "¥${e.coupon}" + if (original != null) "（原价 ¥${String.format(java.util.Locale.US, "%.2f", original)}）" else ""
            )
        }
        val isTransfer = e.type == 2 || e.type == 3
        if (isTransfer) {
            row("转出", e.account)
            row("转入", e.account2)
        } else {
            row("分类", Common.catLabel(e.catename).ifBlank { Common.typeLabel(e.type) })
            row("资产", e.account)
        }
        row("账单时间", e.billtime)
        row("记录时间", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(e.ts)))
        row("来源", appName(e.pkg))
        row("状态", statusText(e.status))

        // 底部操作
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        fun actionButton(text: String, color: Int, onClick: () -> Unit): TextView =
            TextView(ctx).apply {
                this.text = text
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(color)
                setBackgroundResource(R.drawable.bg_secondary)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(10)
                }
                setOnClickListener {
                    onClick()
                    dialog.dismiss()
                }
            }

        if (onResend != null) {
            actions.addView(actionButton("发送至钱迹", ContextCompat.getColor(ctx, R.color.accent)) { onResend() })
        }
        if (onDelete != null) {
            actions.addView(actionButton("删除记录", ContextCompat.getColor(ctx, R.color.danger)) {
                onDelete()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 })
        }
        if (actions.childCount > 0) root.addView(actions)

        dialog.setContentView(root)
        dialog.behavior.isDraggable = true
        dialog.show()
    }

    private fun appName(pkg: String): String = when (pkg) {
        Pkg.ALIPAY -> "支付宝"
        Pkg.WECHAT -> "微信支付"
        Pkg.UNIONPAY -> "云闪付"
        else -> pkg
    }

    /** 数据库里的状态码 → 中文 */
    private fun statusText(status: String): String = when (status.uppercase()) {
        "SENT" -> "已发送至钱迹"
        "DRYRUN" -> "仅本地记录（调试）"
        "QUEUED" -> "待发送"
        "PENDING" -> "待发送"
        "FAIL" -> "发送失败"
        "FAILED" -> "发送失败"
        "DEBUG" -> "仅本地记录（调试）"
        else -> status.ifBlank { "未知" }
    }
}
