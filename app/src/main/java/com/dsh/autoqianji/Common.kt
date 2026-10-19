package com.dsh.autoqianji

import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Common {

    /** 今天 / 昨天 / 具体日期 */
    fun dayLabel(day: Calendar): String {
        val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
        val today = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return when {
            sameDay(day, today) -> "今天 · ${fmt.format(day.time)}"
            sameDay(day, yesterday) -> "昨天 · ${fmt.format(day.time)}"
            else -> fmt.format(day.time)
        }
    }

    /** 列表上方那行统计，首页和记账记录页共用同一套文案 */
    fun summaryLine(total: Int, debug: Int, pending: Int = 0, failed: Int = 0): String {
        if (total == 0) return "0 笔记录"
        val extras = ArrayList<String>()
        if (debug > 0) extras.add("调试 $debug")
        if (pending > 0) extras.add("发送中 $pending")
        if (failed > 0) extras.add("失败 $failed")
        return if (extras.isEmpty()) "$total 笔记录"
        else "$total 笔记录（${extras.joinToString(" · ")}）"
    }

    /** 记录状态角标文案，SENT 不显示角标 */
    fun statusLabel(status: String): String? = when (status) {
        "SENT" -> null
        "DRYRUN" -> "调试"
        "QUEUED" -> "发送中"
        "FAIL" -> "失败"
        else -> status
    }


    /** 类型徽章：颜色随账单类型变化 */
    fun applyTypeBadge(badge: TextView, type: Int) {
        badge.visibility = View.VISIBLE
        badge.text = typeLabel(type)
        val (bg, fg) = when (type) {
            1 -> R.drawable.bg_tag_income to 0xFF4CD964.toInt()
            2 -> R.drawable.bg_tag_queue to 0xFF3FA9F5.toInt()
            3 -> R.drawable.bg_tag_reimburse to 0xFFB39DFF.toInt()
            5 -> R.drawable.bg_tag_report to 0xFF26C6DA.toInt()
            else -> R.drawable.bg_tag_fail to 0xFFFF6B6B.toInt()
        }
        badge.setBackgroundResource(bg)
        badge.setTextColor(fg)
    }

    /** 按状态给角标换颜色 */
    fun applyStatusBadge(badge: TextView, status: String) {
        val label = statusLabel(status)
        if (label == null) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = label
        when (status) {
            "QUEUED" -> {
                badge.setBackgroundResource(R.drawable.bg_tag_queue)
                badge.setTextColor(0xFF3FA9F5.toInt())
            }
            "FAIL" -> {
                badge.setBackgroundResource(R.drawable.bg_tag_fail)
                badge.setTextColor(0xFFFF6B6B.toInt())
            }
            else -> {
                badge.setBackgroundResource(R.drawable.bg_tag_debug)
                badge.setTextColor(0xFFFFB74D.toInt())
            }
        }
    }

    /**
     * 列表行点击：手指滑动（滚动）时不触发，只有原地轻点才算点击。
     * ScrollView / RecyclerView 里直接用 setOnClickListener 容易被滚动误触发。
     */
    fun onTap(view: View, action: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragged = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > slop ||
                        kotlin.math.abs(event.rawY - downY) > slop
                    ) {
                        dragged = true
                    }
                }
            }
            false
        }
        view.setOnClickListener {
            if (!dragged) action()
        }
    }

    /** 钱迹的账单类型 */
    /** 数据库里存的是 一级/::/二级，界面统一显示成 一级 / 二级 */
    fun catLabel(catename: String): String = catename.replace("/::/", " / ")

    fun typeLabel(type: Int): String = when (type) {
        1 -> "收入"
        2 -> "转账"
        3 -> "还款"
        5 -> "报销"
        else -> "支出"
    }

    /** 金额颜色：支出红、收入绿、报销蓝，其余用默认色 */
    fun amountColor(type: Int): Int = when (type) {
        1 -> 0xFF4CD964.toInt()
        5 -> 0xFF3FA9F5.toInt()
        0 -> 0xFFFF6B6B.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    /** 从本地记录还原出一条 Draft，用于「重新发送至钱迹」 */
    fun draftFromLog(e: Store.DayEntry, book: String): Draft = Draft(
        pkg = e.pkg.ifBlank { "manual" },
        amount = e.amount.toDoubleOrNull() ?: 0.0,
        merchant = e.merchant,
        catename = e.catename,
        account = e.account,
        book = book,
        type = e.type,
        remark = remarkOf(e),
        time = e.billtime.ifBlank { null }
    )

    /** 优先从当时提交的 URL 里取出原始备注 */
    private fun remarkOf(e: Store.DayEntry): String {
        val idx = e.detail.indexOf("remark=")
        if (idx >= 0) {
            var v = e.detail.substring(idx + "remark=".length)
            val amp = v.indexOf('&')
            if (amp >= 0) v = v.substring(0, amp)
            return try {
                java.net.URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                v
            }
        }
        return e.merchant
    }

    fun startOfDay(day: Calendar): Long {
        val c = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }
}
