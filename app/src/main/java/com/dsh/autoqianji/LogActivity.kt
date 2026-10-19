package com.dsh.autoqianji

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var selectedDay: java.util.Calendar = java.util.Calendar.getInstance()

    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private var currentIds: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        StickyHeader.attach(this)
        store = Store(this)

        findViewById<View>(R.id.btnPrevDay).setOnClickListener {
            selectedDay.add(java.util.Calendar.DAY_OF_YEAR, -1)
            render()
        }
        findViewById<View>(R.id.btnNextDay).setOnClickListener {
            selectedDay.add(java.util.Calendar.DAY_OF_YEAR, 1)
            render()
        }
        findViewById<TextView>(R.id.tvDayLabel).setOnClickListener {
            DatePick.show(this, selectedDay) { picked ->
                selectedDay.set(
                    picked.get(java.util.Calendar.YEAR),
                    picked.get(java.util.Calendar.MONTH),
                    picked.get(java.util.Calendar.DAY_OF_MONTH)
                )
                render()
            }
        }
        val header = findViewById<PageHeader>(R.id.pageHeader)
        header.addAction("批量管理") {
            selecting = true
            selected.clear()
            render()
        }
        findViewById<View>(R.id.btnSelCancel).setOnClickListener {
            selecting = false
            selected.clear()
            render()
        }
        findViewById<View>(R.id.btnSelAll).setOnClickListener {
            if (selected.size == currentIds.size) selected.clear() else selected.addAll(currentIds)
            render()
        }
        header.addAction("清空调试记录") { confirmClearDebug() }
        findViewById<View>(R.id.btnSelDelete).setOnClickListener { confirmDeleteSelected() }

        render()
    }

    private fun render() {
        val start = (selectedDay.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startMs = start.timeInMillis
        val endMs = startMs + 24 * 60 * 60 * 1000L

        findViewById<TextView>(R.id.tvDayLabel).text = Common.dayLabel(start)

        val entries = store.dayEntries(startMs, endMs)
        val debug = entries.count { it.status == "DRYRUN" }
        val pending = entries.count { it.status == "QUEUED" }
        val failed = entries.count { it.status == "FAIL" }

        val summary = store.daySummary(startMs, endMs)
        findViewById<TextView>(R.id.tvAmount).text =
            String.format(java.util.Locale.US, "¥%.2f", summary.amount)
        val base = Common.summaryLine(entries.size, debug, pending, failed)
        findViewById<TextView>(R.id.tvDaySummary).text =
            if (summary.income > 0) {
                base + " · 收入 ¥" + String.format(java.util.Locale.US, "%.2f", summary.income)
            } else {
                base
            }

        val container = findViewById<LinearLayout>(R.id.listEntries)
        container.removeAllViews()
        findViewById<EmptyState>(R.id.tvEmpty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE

        currentIds = entries.map { it.id }
        selected.retainAll(currentIds.toSet())
        findViewById<View>(R.id.selBar).visibility = if (selecting) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tvSelCount).text =
            if (selected.isEmpty()) "已选 0 项" else "已选 ${selected.size} 项"

        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
        for (e in entries) {
            val row = layoutInflater.inflate(R.layout.item_entry, container, false)
            row.findViewById<TextView>(R.id.tvEntryTime).text =
                fmt.format(java.util.Date(e.ts))
            val billView = row.findViewById<TextView>(R.id.tvEntryBillTime)
            val billTime = if (e.billtime.isNotBlank()) {
                runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .parse(e.billtime)
                }.getOrNull()
            } else {
                null
            }
            if (billTime != null) {
                billView.text = "账单 " + fmt.format(billTime)
                billView.visibility = View.VISIBLE
            } else {
                billView.visibility = View.GONE
            }
            row.findViewById<TextView>(R.id.tvEntryMerchant).text =
                e.merchant.ifBlank { "未知商户" }
            val typeBadge = row.findViewById<TextView>(R.id.tvEntryType)
            val categoryView = row.findViewById<TextView>(R.id.tvEntryCategory)
            if (e.catename.isBlank()) {
                // 转账/还款没有分类：照样显示类型角标，右下角留空
                Common.applyTypeBadge(typeBadge, e.type)
                categoryView.visibility = View.GONE
            } else {
                Common.applyTypeBadge(typeBadge, e.type)
                categoryView.text = Common.catLabel(e.catename)
                if (e.coupon.isNotBlank()) {
                    categoryView.text = categoryView.text.toString() + "（优惠¥${e.coupon}）"
                }
                categoryView.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            }
            categoryView.visibility = View.VISIBLE
            val accountView = row.findViewById<TextView>(R.id.tvEntryAccount)
            val isTransferRow = e.type == 2 || e.type == 3
            accountView.text = if (isTransferRow && e.account2.isNotBlank()) {
                "${e.account} → ${e.account2}"
            } else {
                e.account
            }
            accountView.visibility =
                if (e.account.isBlank() && e.account2.isBlank()) View.GONE else View.VISIBLE
            row.findViewById<TextView>(R.id.tvEntryAmount).setTextColor(Common.amountColor(e.type))
            row.findViewById<TextView>(R.id.tvEntryAmount).text = "¥${e.amount}"
            Common.applyStatusBadge(row.findViewById(R.id.tvEntryDebug), e.status)

            val cb = row.findViewById<android.widget.ImageView>(R.id.cbEntry)
            cb.visibility = if (selecting) View.VISIBLE else View.GONE
            cb.setImageResource(
                if (selected.contains(e.id)) R.drawable.cb_checked else R.drawable.cb_unchecked
            )
            // 单击：选择模式切换勾选，否则弹出详情；滑动不触发
            Common.onTap(row) {
                if (selecting) {
                    if (selected.contains(e.id)) selected.remove(e.id) else selected.add(e.id)
                    render()
                } else {
                    EntrySheet.show(this, e,
                        onResend = { resendToQianji(e) },
                        onDelete = { confirmDelete(e) })
                }
            }
            row.setOnLongClickListener {
                if (selecting) {
                    if (selected.contains(e.id)) selected.remove(e.id) else selected.add(e.id)
                    render()
                    return@setOnLongClickListener true
                }
                androidx.appcompat.widget.PopupMenu(this, row).apply {
                    menu.add(0, 1, 0, "发送至钱迹")
                    menu.add(0, 2, 1, "删除记录")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> resendToQianji(e)
                            2 -> confirmDelete(e)
                        }
                        true
                    }
                    show()
                }
                true
            }
            container.addView(row)
        }
    }

    /** 批量删除勾选的本地记录，钱迹里的账单不动 */
    private fun confirmDeleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "先勾选要删除的记录", Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        AlertDialog.Builder(this)
            .setTitle("删除 $n 条记录")
            .setMessage("只删本地记录，钱迹里已记的账单不会变。")
            .setPositiveButton("删除") { _, _ ->
                for (id in selected.toList()) store.deleteLog(id)
                Toast.makeText(this, "已删除 $n 条记录", Toast.LENGTH_SHORT).show()
                selecting = false
                selected.clear()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按 → 发送至钱迹：不走队列、不判重，直接重发一次 */
    private fun resendToQianji(e: Store.DayEntry) {
        val draft = Common.draftFromLog(e, Config.book(this))
        if (draft.amount <= 0) {
            Toast.makeText(this, "金额异常，没法重发", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = Submitter.resendNow(this, draft, e.id)
        Toast.makeText(
            this,
            if (ok) "已发送至钱迹" else "发送失败，检查后台弹出界面权限",
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    /** 长按记录：只删本地日志，不动钱迹里已记的账单 */
    private fun confirmDelete(e: Store.DayEntry) {
        AlertDialog.Builder(this)
            .setMessage(
                "删除这条记录？\n\n" +
                    "${e.merchant}  ¥${e.amount}\n\n" +
                    "只删本地记录，钱迹里已记的账单不会变。"
            )
            .setPositiveButton("删除") { _, _ ->
                store.deleteLog(e.id)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearDebug() {
        val n = store.debugLogCount()
        if (n == 0) {
            Toast.makeText(this, "没有调试记录", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空调试记录")
            .setMessage("将删除全部 $n 条未提交的调试记录，已提交的记录不受影响。")
            .setPositiveButton("清空") { _, _ ->
                val removed = store.clearDebugLogs()
                Toast.makeText(this, "已删除 $removed 条", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
