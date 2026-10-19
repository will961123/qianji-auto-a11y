package com.dsh.autoqianji

import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 手动记一笔。
 * 类型对齐钱迹接口的合法值：0 支出 / 1 收入 / 2 转账 / 3 还款 / 5 报销。
 * 转账、还款要两个账户，可以带手续费；其余类型要分类，可以带优惠。
 */
class ManualBillActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var card: LinearLayout

    private var type = 0
    private var catename = ""
    private var account = ""
    private var account2 = ""
    private var book = ""
    private var time: Calendar = Calendar.getInstance()

    private var amountText = ""
    private var couponText = ""
    private var feeText = ""
    private var remarkText = ""

    private lateinit var etAmount: EditText
    private lateinit var etCoupon: EditText
    private lateinit var etFee: EditText
    private lateinit var etRemark: EditText
    private lateinit var tvType: TextView
    private lateinit var tvCate: TextView
    private lateinit var tvAccount: TextView
    private lateinit var tvAccount2: TextView
    private lateinit var tvBook: TextView
    private lateinit var tvTime: TextView

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val sendFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private val isTransfer get() = type == 2 || type == 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)
        StickyHeader.attach(this)
        store = Store(this)
        card = findViewById(R.id.cardForm)

        intent.getIntExtra("type", 0).let { if (it in TYPE_VALUES) type = it }
        account = Config.defaultAccount(this)
        book = Config.book(this)
        catename = Config.defaultCategory(this).ifBlank { "其它" }

        // 从悬浮窗「修改」进来时带上识别结果
        intent.getStringExtra("amount")?.let { amountText = it }
        intent.getStringExtra("catename")?.takeIf { it.isNotBlank() }?.let { catename = it }
        intent.getStringExtra("account")?.takeIf { it.isNotBlank() }?.let { account = it }
        intent.getStringExtra("remark")?.takeIf { it.isNotBlank() }?.let { remarkText = it }
        intent.getStringExtra("coupon")?.takeIf { it.isNotBlank() }?.let { couponText = it }
        intent.getIntExtra("type", -1).takeIf { it in listOf(0, 1, 2, 3, 5) }?.let { type = it }
        intent.getStringExtra("timeMillis")?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(raw)
            }.getOrNull()?.let { time.timeInMillis = it.time }
        }

        buildForm()
        findViewById<PageHeader>(R.id.pageHeader).addAction("提交") { submit() }
    }

    private fun buildForm() {
        card.removeAllViews()

        etAmount = inputRow(R.drawable.ic_line_cash, "金额", "0.00", numberInput())
        etAmount.setText(amountText)

        tvType = pickerRow(R.drawable.ic_line_swap, "类型", typeText()) {
            keepInputs()
            pick("类型", TYPE_LABELS, typeText()) { picked ->
                type = TYPE_LABELS.indexOf(picked).let { TYPE_VALUES.getOrElse(it) { 0 } }
                buildForm()
            }
        }

        if (isTransfer) {
            tvAccount = pickerRow(R.drawable.ic_line_wallet, "转出账户", account.ifBlank { "未选择" }) {
                pick("选择转出账户", store.assets(), account) { picked ->
                    account = picked
                    tvAccount.text = account.ifBlank { "未选择" }
                }
            }
            tvAccount2 = pickerRow(R.drawable.ic_line_wallet, "转入账户", account2.ifBlank { "未选择" }) {
                pick("选择转入账户", store.assets(), account2) { picked ->
                    account2 = picked
                    tvAccount2.text = account2.ifBlank { "未选择" }
                }
            }
        } else {
            tvCate = pickerRow(R.drawable.ic_line_folder, "分类", displayOf(catename).ifBlank { "未选择" }) {
                pickCategory()
            }
            tvAccount = pickerRow(R.drawable.ic_line_wallet, "资产", account.ifBlank { "未选择" }) {
                pick("选择资产", listOf(NONE) + store.assets(), account.ifBlank { NONE }) { picked ->
                    account = if (picked == NONE) "" else picked
                    tvAccount.text = account.ifBlank { "未选择" }
                }
            }
        }

        tvBook = pickerRow(R.drawable.ic_line_book, "账本", book.ifBlank { "未选择" }) {
            pick("选择账本", listOf(NONE) + store.books(), book.ifBlank { NONE }) { picked ->
                book = if (picked == NONE) "" else picked
                tvBook.text = book.ifBlank { "未选择" }
            }
        }
        tvTime = pickerRow(R.drawable.ic_line_clock, "时间", timeFmt.format(time.time)) { pickTime() }

        if (isTransfer) {
            etFee = inputRow(R.drawable.ic_line_receipt, "手续费", "0.00（可不填）", numberInput())
            etFee.setText(feeText)
        } else {
            etCoupon = inputRow(R.drawable.ic_line_discount, "优惠金额", "0.00（可不填）", numberInput())
            etCoupon.setText(couponText)
        }

        etRemark = inputRow(R.drawable.ic_line_note, "备注", "手动记账", InputType.TYPE_CLASS_TEXT)
        etRemark.setText(remarkText)
    }

    /** 重建表单前把已经输入的内容存下来 */
    private fun keepInputs() {
        if (::etAmount.isInitialized) amountText = etAmount.text.toString()
        if (::etRemark.isInitialized) remarkText = etRemark.text.toString()
        if (::etCoupon.isInitialized) couponText = etCoupon.text.toString()
        if (::etFee.isInitialized) feeText = etFee.text.toString()
    }

    private fun numberInput() =
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

    private fun typeText() = TYPE_LABELS[TYPE_VALUES.indexOf(type).coerceAtLeast(0)]

    // ---------------- 行构建 ----------------

    private fun baseRow(icon: Int, title: String): LinearLayout {
        val row = layoutInflater.inflate(R.layout.item_setting, card, false) as LinearLayout
        row.findViewById<ImageView>(R.id.ivSettingIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.tvSettingTitle).text = title
        row.findViewById<TextView>(R.id.tvSettingSub).visibility = View.GONE
        row.findViewById<View>(R.id.ivSettingArrow).visibility = View.GONE
        card.addView(row)
        return row
    }

    private fun pickerRow(icon: Int, title: String, value: String, onClick: () -> Unit): TextView {
        val row = baseRow(icon, title)
        val tv = TextView(this).apply {
            text = value
            textSize = 14f
            alpha = 0.8f
            gravity = Gravity.END
        }
        row.addView(tv)
        row.setOnClickListener { onClick() }
        return tv
    }

    private fun inputRow(icon: Int, title: String, hint: String, inputType: Int): EditText {
        val row = baseRow(icon, title)
        val et = EditText(this).apply {
            this.hint = hint
            textSize = 14f
            gravity = Gravity.END
            setSingleLine()
            this.inputType = inputType
            background = null
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return et
    }

    // ---------------- 选择器 ----------------

    private fun pick(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        if (options.isEmpty()) {
            toast("还没导入数据，先回首页导入")
            return
        }
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(20), 0, dp(20), dp(8))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, dp(280))

        var selected: View? = null
        for (option in options) {
            val tv = listRow(option, option == current)
            if (option == current) selected = tv
            tv.setOnClickListener {
                onPick(option)
                dialog.dismiss()
            }
            list.addView(tv)
        }
        if (selected != null) {
            val target = selected
            scroll.post {
                scroll.scrollTo(0, (target.top - (scroll.height - target.height) / 2).coerceAtLeast(0))
            }
        }

        dialog.setContentView(root)
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    private fun pickCategory() {
        val all = store.categoryTree()
        val roots = all.filter { !it.isChild }
        if (roots.isEmpty()) {
            toast("还没导入分类，先回首页导入")
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = View.inflate(this, R.layout.sheet_category, null) as LinearLayout
        dialog.setContentView(view)

        val left = view.findViewById<LinearLayout>(R.id.catLeft)
        val right = view.findViewById<LinearLayout>(R.id.catRight)
        val leftScroll = view.findViewById<ScrollView>(R.id.catLeftScroll)
        val rightScroll = view.findViewById<ScrollView>(R.id.catRightScroll)

        var selectedRoot = roots.firstOrNull { r ->
            catename == r.name || catename.startsWith("${r.name}/::/")
        } ?: roots.first()

        fun choose(value: String) {
            catename = value
            tvCate.text = displayOf(value).ifBlank { "未选择" }
            dialog.dismiss()
        }

        fun paint() {
            left.removeAllViews()
            var selLeft: View? = null
            for (root in roots) {
                val tv = listRow(root.name, root.name == selectedRoot.name)
                if (root.name == selectedRoot.name) selLeft = tv
                tv.setOnClickListener {
                    selectedRoot = root
                    paint()
                }
                left.addView(tv)
            }

            right.removeAllViews()
            var selRight: View? = null
            val whole = listRow("（整个分类）", catename == selectedRoot.name)
            if (catename == selectedRoot.name) selRight = whole
            whole.setOnClickListener { choose(selectedRoot.name) }
            right.addView(whole)

            for (kid in all.filter { it.isChild && it.parent == selectedRoot.name }) {
                val value = "${selectedRoot.name}/::/${kid.name}"
                val tv = listRow(kid.name, catename == value)
                if (catename == value) selRight = tv
                tv.setOnClickListener { choose(value) }
                right.addView(tv)
            }

            val l = selLeft
            if (l != null) leftScroll.post {
                leftScroll.scrollTo(0, (l.top - (leftScroll.height - l.height) / 2).coerceAtLeast(0))
            }
            val r = selRight
            if (r != null) rightScroll.post {
                rightScroll.scrollTo(0, (r.top - (rightScroll.height - r.height) / 2).coerceAtLeast(0))
            }
        }

        val clear = listRow("不选分类", catename.isBlank())
        clear.setOnClickListener { choose("") }
        view.addView(clear)

        paint()
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    private fun pickTime() {
        DatePick.show(this, time) { picked ->
            time.set(
                picked.get(Calendar.YEAR),
                picked.get(Calendar.MONTH),
                picked.get(Calendar.DAY_OF_MONTH)
            )
            pickClock()
        }
    }

    /** 时 / 分 / 秒三个滚轮，系统 TimePickerDialog 不能选秒 */
    private fun pickClock() {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(TextView(this).apply {
            text = "选择时间"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(20), 0, dp(20), dp(8))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        val hour = numberPicker(0, 23, time.get(Calendar.HOUR_OF_DAY))
        val minute = numberPicker(0, 59, time.get(Calendar.MINUTE))
        val second = numberPicker(0, 59, time.get(Calendar.SECOND))
        for (picker in listOf(hour, minute, second)) {
            row.addView(picker, LinearLayout.LayoutParams(0, dp(180), 1f))
        }
        root.addView(row)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val cancel = TextView(this).apply {
            text = "取消"
            textSize = 15f
            gravity = Gravity.CENTER
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { dialog.dismiss() }
        }
        val ok = TextView(this).apply {
            text = "确定"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFB39DFF.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                time.set(Calendar.HOUR_OF_DAY, hour.value)
                time.set(Calendar.MINUTE, minute.value)
                time.set(Calendar.SECOND, second.value)
                tvTime.text = timeFmt.format(time.time)
                dialog.dismiss()
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        dialog.setContentView(root)
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    private fun numberPicker(min: Int, max: Int, current: Int): NumberPicker =
        NumberPicker(this).apply {
            this.minValue = min
            this.maxValue = max
            value = current
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.US, "%02d", it) }
        }

    private fun listRow(text: String, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(20), dp(14), dp(20), dp(14))
            gravity = Gravity.CENTER_VERTICAL
                        setTextColor(
                if (selected) androidx.core.content.ContextCompat.getColor(context, R.color.accent)
                else androidx.core.content.ContextCompat.getColor(context, R.color.text_primary)
            )
            if (selected) {
                setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF or 0x33000000
                )
                setTypeface(null, Typeface.BOLD)
            }
        }

    // ---------------- 提交 ----------------

    private fun submit() {
        val amount = etAmount.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            toast("请填写金额")
            return
        }
        val remarkTextValue = etRemark.text.toString().trim().ifBlank { "手动记账" }
        val remark = "[插件-手动] $remarkTextValue"

        var coupon = ""
        var fee = ""
        if (isTransfer) {
            if (account.isBlank() || account2.isBlank()) {
                toast("转账/还款要选转出和转入账户")
                return
            }
            if (account == account2) {
                toast("转出和转入不能是同一个账户")
                return
            }
            val feeValue = etFee.text.toString().trim().toDoubleOrNull()
            if (feeValue != null && feeValue > 0) {
                if (feeValue > amount) {
                    toast("手续费不能大于金额")
                    return
                }
                fee = String.format(Locale.US, "%.2f", feeValue)
            }
        } else {
            val couponValue = etCoupon.text.toString().trim().toDoubleOrNull()
            if (couponValue != null && couponValue > 0) {
                if (couponValue > amount) {
                    toast("优惠金额不能大于金额")
                    return
                }
                coupon = String.format(Locale.US, "%.2f", couponValue)
            }
        }
        // 本地记录：金额按优惠后实付；转账/还款没有分类，用类型名占位，账户写成「转出 → 转入」
        val netAmount = if (coupon.isNotBlank()) amount - (coupon.toDoubleOrNull() ?: 0.0) else amount
        val logCatename = catename
        val logAccount = if (isTransfer) {
            buildString {
                append(account).append(" → ").append(account2)
                if (fee.isNotBlank()) append("（手续费¥").append(fee).append("）")
            }
        } else if (coupon.isNotBlank()) {
            "$account（优惠¥$coupon）"
        } else {
            account
        }

        val draft = Draft(
            pkg = "manual",
            amount = amount,
            merchant = remarkTextValue,
            catename = if (isTransfer) "" else catename,
            account = account,
            book = book,
            type = type,
            remark = remark,
            time = sendFmt.format(time.time),
            coupon = coupon,
            account2 = if (isTransfer) account2 else "",
            fee = fee,
            logAmount = netAmount,
            logCatename = logCatename,
            logAccount = logAccount
        )
        Submitter.submit(this, draft)
        toast("已提交")
        finish()
    }

    private fun displayOf(value: String): String {
        if (value.isBlank()) return ""
        val parts = value.split("/::/")
        return if (parts.size == 2) "${parts[0]} / ${parts[1]}" else value
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val NONE = "（不设置）"
        val TYPE_LABELS = listOf("支出", "收入", "转账", "还款", "报销")
        val TYPE_VALUES = listOf(0, 1, 2, 3, 5)
    }
}
