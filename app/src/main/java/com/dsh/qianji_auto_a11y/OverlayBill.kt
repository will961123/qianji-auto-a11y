package com.dsh.qianji_auto_a11y

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 浮在支付页面上的半屏面板：
 * - 重复账单先问一句，确认后才展开完整内容（以后接模型也不会白调一次）
 * - 金额、类型、分类、资产都能在面板里直接改，不用跳回 App
 */
object OverlayBill {


    private const val WHOLE = "（整个分类）"

    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var session: Session? = null
    private class Session(
        val ctx: Context,
        var draft: Draft,
        var alreadyLogged: Boolean,
        var onSubmit: (Draft) -> Unit,
        var onDismiss: () -> Unit = {}
    )

    fun isShowing(): Boolean = root != null

    fun dismiss() {
        val view = root ?: return
        root = null
        session = null
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        windowManager = null
        layoutParams = null
    }

    /** 重复账单的确认：只有点「继续记录」才会展开完整面板 */
    fun showDuplicate(
        ctx: Context,
        draft: Draft,
        onContinue: () -> Unit,
        onCancel: () -> Unit
    ) {
        val view = attach(ctx, R.layout.overlay_duplicate) ?: return
        vibrate(ctx)
        view.findViewById<TextView>(R.id.dupSummary).text = listOfNotNull(
            String.format(java.util.Locale.US, "¥%.2f", draft.amount),
            draft.merchant.ifBlank { null },
            draft.time
        ).joinToString(" · ")
        view.findViewById<View>(R.id.dupCancel).setOnClickListener {
            dismiss()
            onCancel()
        }
        view.findViewById<View>(R.id.dupContinue).setOnClickListener {
            dismiss()
            onContinue()
        }
    }

    /** 完整面板：可直接编辑金额、类型、分类、资产 */
    fun showSheet(
        ctx: Context,
        draft: Draft,
        alreadyLogged: Boolean = false,
        onDismiss: () -> Unit = {},
        onSubmit: (Draft) -> Unit
    ) {
        val container = attachContainer(ctx) ?: return
        vibrate(ctx)
        val s = Session(ctx, draft, alreadyLogged, onSubmit, onDismiss)
        session = s
        runCatching { renderForm(container, s) }.onFailure {
            android.util.Log.e("AutoQianji", "renderForm failed", it)
            dismiss()
        }
    }

    // ---------------- 表单 ----------------

    private fun renderForm(container: FrameLayout, s: Session) {
        container.removeAllViews()
        container.requestApplyInsets()
        anchor(container, atTop = false)
        val view = View.inflate(themed(s.ctx), R.layout.overlay_sheet, null)
        container.addView(view)

        view.findViewById<TextView>(R.id.overlayBadge).visibility =
            if (s.alreadyLogged) View.VISIBLE else View.GONE

        val amount = view.findViewById<TextView>(R.id.sheetAmount)
        amount.text = String.format(java.util.Locale.US, "%.2f", s.draft.amount)
        amount.setOnClickListener { showAmountEditor(container, s) }

        val rowType = view.findViewById<TextView>(R.id.rowType)
        val rowCate = view.findViewById<TextView>(R.id.rowCate)
        val rowAccount = view.findViewById<TextView>(R.id.rowAccount)
        val rowTime = view.findViewById<TextView>(R.id.rowTime)
        val rowRemark = view.findViewById<TextView>(R.id.rowRemark)

        fun refresh() {
            // 金额输入框只放数字；有优惠时在「¥」左边显示「原价」标签
        view.findViewById<View>(R.id.sheetPriceTag).visibility =
            if (s.draft.coupon.isNotBlank()) View.VISIBLE else View.GONE
        setRow(rowType, "类型", Common.typeLabel(s.draft.type))
            val isTransfer = s.draft.type == 2 || s.draft.type == 3
        rowCate.visibility = if (isTransfer) View.GONE else View.VISIBLE
        if (!isTransfer) {
            setRow(rowCate, "分类", displayOf(s.draft.catename).ifBlank { "未选择" })
        }
            val label = if (isTransfer) "转出" else "资产"
        setRow(rowAccount, label, s.draft.account.ifBlank { "未选择" })
        val rowAccount2 = view.findViewById<TextView>(R.id.rowAccount2)
        rowAccount2.visibility = if (isTransfer) View.VISIBLE else View.GONE
        if (isTransfer) {
            setRow(rowAccount2, "转入", s.draft.account2.ifBlank { "未选择" })
            rowAccount2.setOnClickListener { showAssetColumns(container, s, forAccount2 = true) }
        } else {
            rowAccount2.setOnClickListener(null)
        }
        val rowCoupon = view.findViewById<View>(R.id.rowCoupon)
        val couponValue = view.findViewById<TextView>(R.id.tvCouponValue)
        val couponNet = view.findViewById<TextView>(R.id.tvCouponNet)
        if (s.draft.coupon.isNotBlank()) {
            rowCoupon.visibility = View.VISIBLE
            couponValue.text = s.draft.coupon
            couponValue.setTextColor(
                androidx.core.content.ContextCompat.getColor(view.context, R.color.text_primary)
            )
            val net = s.draft.logAmount
            couponNet.text = if (net != null) {
                "（实付 ¥${String.format(java.util.Locale.US, "%.2f", net)}）"
            } else {
                ""
            }
            rowCoupon.setOnClickListener { showCouponEditor(container, s) }
        } else {
            rowCoupon.visibility = View.GONE
            rowCoupon.setOnClickListener(null)
        }
            setRow(rowTime, "时间", s.draft.time ?: "现在")
            setRow(rowRemark, "备注", remarkText(s.draft).ifBlank { "未填写" })
        }
        refresh()

        rowRemark.setOnClickListener { showRemarkEditor(container, s) }
        rowTime.setOnClickListener { showTimeEditor(container, s) }

        rowType.setOnClickListener {
            val labels = listOf("支出", "收入", "转账", "还款", "报销")
            val values = listOf(0, 1, 2, 3, 5)
            showList(
                container, s, "选择类型", labels,
                current = values.indexOf(s.draft.type)
            ) { index ->
                s.draft = s.draft.copy(type = values[index])
                renderForm(container, s)
            }
        }

        rowCate.setOnClickListener { showCategoryColumns(container, s) }

        rowAccount.setOnClickListener { showAssetColumns(container, s) }

        view.findViewById<View>(R.id.overlayClose).setOnClickListener {
            dismiss()
            s.onDismiss()
        }
        view.findViewById<View>(R.id.overlayCancel).setOnClickListener {
            dismiss()
            s.onDismiss()
        }
        view.findViewById<View>(R.id.overlaySubmit).setOnClickListener {
            val typed = amount.text.toString().trim().toDoubleOrNull()
            if (typed == null || typed <= 0) {
                amount.error = "请输入金额"
                return@setOnClickListener
            }
            val finished = s.draft.copy(amount = typed)
            dismiss()
            s.onSubmit(finished)
        }
    }

    /** 备注单独一页，多行输入，键盘不会挡住 */
    private fun showRemarkEditor(container: FrameLayout, s: Session) {
        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_remark, null)
        container.addView(view)

        anchor(container, atTop = true)
        val input = view.findViewById<EditText>(R.id.remarkInput)
        input.setText(remarkText(s.draft))
        input.setSelection(input.text.length)
        input.requestFocus()

        input.postDelayed({
            val imm = s.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120L)

        view.findViewById<View>(R.id.remarkBack).setOnClickListener { renderForm(container, s) }
        view.findViewById<View>(R.id.remarkConfirm).setOnClickListener {
            val text = input.text.toString().trim()
            s.draft = s.draft.copy(
                merchant = text,
                remark = remarkPrefix(s.draft) + text
            )
            renderForm(container, s)
        }
    }

    /** 金额编辑：单独一页，数字键盘 */
    private fun showAmountEditor(container: FrameLayout, s: Session) {
        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_remark, null)
        container.addView(view)
        anchor(container, atTop = true)

        view.findViewById<TextView>(R.id.tvRemarkTitle).text = "账单金额"
        val input = view.findViewById<EditText>(R.id.remarkInput)
        input.hint = "填写金额，例如 185.60"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.minLines = 1
        input.maxLines = 1
        input.setText(String.format(java.util.Locale.US, "%.2f", s.draft.amount))
        input.setSelection(input.text.length)
        input.requestFocus()
        input.postDelayed({
            val imm = s.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120L)

        view.findViewById<View>(R.id.remarkBack).setOnClickListener { renderForm(container, s) }
        view.findViewById<View>(R.id.remarkConfirm).setOnClickListener {
            val value = input.text.toString().trim().toDoubleOrNull()
            if (value != null && value > 0.0) {
                val discount = s.draft.coupon.toDoubleOrNull() ?: 0.0
                val net = if (s.draft.coupon.isNotBlank()) (value - discount).coerceAtLeast(0.0) else null
                s.draft = s.draft.copy(amount = value, logAmount = net)
            }
            renderForm(container, s)
        }
    }

    /** 优惠金额编辑：数字键盘，改完重算实付 */
    private fun showCouponEditor(container: FrameLayout, s: Session) {
        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_remark, null)
        container.addView(view)
        anchor(container, atTop = true)

        view.findViewById<TextView>(R.id.tvRemarkTitle).text = "优惠金额"
        view.findViewById<TextView>(R.id.remarkInput).hint = "填写优惠金额，例如 1.00"
        val input = view.findViewById<EditText>(R.id.remarkInput)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.minLines = 1
        input.maxLines = 1
        input.setText(s.draft.coupon)
        input.setSelection(input.text.length)
        input.requestFocus()
        input.postDelayed({
            val imm = s.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120L)

        view.findViewById<View>(R.id.remarkBack).setOnClickListener { renderForm(container, s) }
        view.findViewById<View>(R.id.remarkConfirm).setOnClickListener {
            val value = input.text.toString().trim().toDoubleOrNull() ?: 0.0
            val coupon = if (value > 0.0) String.format(java.util.Locale.US, "%.2f", value) else ""
            val net = if (coupon.isBlank()) null else (s.draft.amount - value).coerceAtLeast(0.0)
            s.draft = s.draft.copy(coupon = coupon, logAmount = net)
            renderForm(container, s)
        }
    }

    private fun remarkPrefix(draft: Draft): String = when {
        draft.remark.startsWith("[插件-手动] ") -> "[插件-手动] "
        draft.remark.startsWith("[插件-自动] ") -> "[插件-自动] "
        else -> "[插件-自动] "
    }

    private fun remarkText(draft: Draft): String =
        draft.remark.removePrefix(remarkPrefix(draft)).ifBlank { draft.merchant }

    /** 时间编辑：日期 + 时/分/秒，全部在面板内完成 */
    private fun showTimeEditor(container: FrameLayout, s: Session) {
        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_time, null)
        container.addView(view)

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        val cal = java.util.Calendar.getInstance()
        val current = s.draft.time?.let { runCatching { fmt.parse(it) }.getOrNull() }
        if (current != null) cal.time = current

        val date = view.findViewById<android.widget.DatePicker>(R.id.timeDate)
        date.updateDate(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )

        val hour = view.findViewById<android.widget.NumberPicker>(R.id.timeHour)
        val minute = view.findViewById<android.widget.NumberPicker>(R.id.timeMinute)
        val second = view.findViewById<android.widget.NumberPicker>(R.id.timeSecond)
        hour.minValue = 0
        hour.maxValue = 23
        hour.value = cal.get(java.util.Calendar.HOUR_OF_DAY)
        hour.wrapSelectorWheel = true
        minute.minValue = 0
        minute.maxValue = 59
        minute.value = cal.get(java.util.Calendar.MINUTE)
        minute.wrapSelectorWheel = true
        second.minValue = 0
        second.maxValue = 59
        second.value = cal.get(java.util.Calendar.SECOND)
        second.wrapSelectorWheel = true
        for (picker in listOf(hour, minute, second)) {
            picker.setFormatter { String.format(java.util.Locale.US, "%02d", it) }
        }

        view.findViewById<View>(R.id.timeBack).setOnClickListener { renderForm(container, s) }
        view.findViewById<View>(R.id.timeConfirm).setOnClickListener {
            val picked = java.util.Calendar.getInstance().apply {
                set(
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                    hour.value, minute.value, second.value
                )
                set(java.util.Calendar.MILLISECOND, 0)
            }
            s.draft = s.draft.copy(time = fmt.format(picked.time))
            renderForm(container, s)
        }
    }

    /** 分类：沿用设置页那套左一级 / 右二级的滚动选择 */
    private fun showCategoryColumns(container: FrameLayout, s: Session) {
        val tree = Store(s.ctx).categoryTree()
        val roots = tree.filter { !it.isChild }
        if (roots.isEmpty()) return

        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_columns, null)
        container.addView(view)
        view.findViewById<TextView>(R.id.colTitle).text = "选择分类"

        val left = view.findViewById<LinearLayout>(R.id.colLeft)
        val right = view.findViewById<LinearLayout>(R.id.colRight)
        val leftScroll = view.findViewById<android.widget.ScrollView>(R.id.colLeftScroll)
        val rightScroll = view.findViewById<android.widget.ScrollView>(R.id.colRightScroll)

        var selectedRoot = roots.firstOrNull {
            s.draft.catename == it.name || s.draft.catename.startsWith(it.name + "/::/")
        } ?: roots.first()

        fun choose(value: String) {
            s.draft = s.draft.copy(catename = value)
            renderForm(container, s)
        }

        fun paint() {
            left.removeAllViews()
            var leftMark: View? = null
            for (root in roots) {
                val tv = pickerRow(s.ctx, root.name, root.name == selectedRoot.name)
                if (root.name == selectedRoot.name) leftMark = tv
                tv.setOnClickListener {
                    selectedRoot = root
                    paint()
                }
                left.addView(tv)
            }

            right.removeAllViews()
            var rightMark: View? = null
            val whole = pickerRow(s.ctx, WHOLE, s.draft.catename == selectedRoot.name)
            if (s.draft.catename == selectedRoot.name) rightMark = whole
            whole.setOnClickListener { choose(selectedRoot.name) }
            right.addView(whole)

            for (kid in tree.filter { it.isChild && it.parent == selectedRoot.name }) {
                val value = selectedRoot.name + "/::/" + kid.name
                val tv = pickerRow(s.ctx, kid.name, s.draft.catename == value)
                if (s.draft.catename == value) rightMark = tv
                tv.setOnClickListener { choose(value) }
                right.addView(tv)
            }

            leftMark?.let { v ->
                leftScroll.post { leftScroll.scrollTo(0, (v.top - leftScroll.height / 2).coerceAtLeast(0)) }
            }
            rightMark?.let { v ->
                rightScroll.post { rightScroll.scrollTo(0, (v.top - rightScroll.height / 2).coerceAtLeast(0)) }
            }
        }

        view.findViewById<View>(R.id.colBack).setOnClickListener { renderForm(container, s) }
        paint()
    }

    /** 资产：单列滚动选择 */
    private fun showAssetColumns(container: FrameLayout, s: Session, forAccount2: Boolean = false) {
        val assets = Store(s.ctx).assets()
        if (assets.isEmpty()) return

        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_columns, null)
        container.addView(view)
        view.findViewById<TextView>(R.id.colTitle).text =
            if (forAccount2) "选择转入账户" else "选择资产"
        view.findViewById<View>(R.id.colDivider).visibility = View.GONE
        view.findViewById<View>(R.id.colRightScroll).visibility = View.GONE

        val left = view.findViewById<LinearLayout>(R.id.colLeft)
        val leftScroll = view.findViewById<android.widget.ScrollView>(R.id.colLeftScroll)
        val current = if (forAccount2) s.draft.account2 else s.draft.account
        var mark: View? = null
        for (name in assets) {
            val tv = pickerRow(s.ctx, name, name == current)
            if (name == current) mark = tv
            tv.setOnClickListener {
                s.draft = if (forAccount2) s.draft.copy(account2 = name) else s.draft.copy(account = name)
                renderForm(container, s)
            }
            left.addView(tv)
        }
        mark?.let { v ->
            leftScroll.post { leftScroll.scrollTo(0, (v.top - leftScroll.height / 2).coerceAtLeast(0)) }
        }

        view.findViewById<View>(R.id.colBack).setOnClickListener { renderForm(container, s) }
    }

    private fun pickerRow(ctx: Context, text: String, selected: Boolean): TextView =
        TextView(themed(ctx)).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(ctx, 10), dp(ctx, 13), dp(ctx, 10), dp(ctx, 13))
            val accent = androidx.core.content.ContextCompat.getColor(context, R.color.accent)
            val primary = androidx.core.content.ContextCompat.getColor(context, R.color.text_primary)
            setTextColor(if (selected) accent else primary)
            if (selected) {
                setBackgroundColor(accent and 0x00FFFFFF or 0x33000000)
                setTypeface(null, Typeface.BOLD)
            }
        }

    private fun categoryOptions(ctx: Context): List<Pair<String, String>> {
        val all = Store(ctx).categoryTree()
        val out = ArrayList<Pair<String, String>>()
        for (root in all.filter { !it.isChild }) {
            out.add(root.name to root.name)
            for (kid in all.filter { it.isChild && it.parent == root.name }) {
                out.add("${root.name} / ${kid.name}" to "${root.name}/::/${kid.name}")
            }
        }
        return out
    }

    // ---------------- 选择列表 ----------------

    private fun showList(
        container: FrameLayout,
        s: Session,
        title: String,
        labels: List<String>,
        current: Int = -1,
        onPick: (Int) -> Unit
    ) {
        container.removeAllViews()
        val view = View.inflate(themed(s.ctx), R.layout.overlay_list, null)
        container.addView(view)
        view.findViewById<TextView>(R.id.listTitle).text = title
        view.findViewById<View>(R.id.listBack).setOnClickListener { renderForm(container, s) }

        val list = view.findViewById<LinearLayout>(R.id.listContainer)
        val accent = androidx.core.content.ContextCompat.getColor(s.ctx, R.color.accent)
        val primary = androidx.core.content.ContextCompat.getColor(s.ctx, R.color.text_primary)
        for ((index, label) in labels.withIndex()) {
            val selected = index == current
            val tv = TextView(themed(s.ctx)).apply {
                text = label
                textSize = 15f
                setPadding(dp(s.ctx, 10), dp(s.ctx, 14), dp(s.ctx, 10), dp(s.ctx, 14))
                setTextColor(if (selected) accent else primary)
                if (selected) {
                    setBackgroundColor(accent and 0x00FFFFFF or 0x33000000)
                    setTypeface(null, Typeface.BOLD)
                }
                setOnClickListener {
                    onPick(index)
                }
            }
            list.addView(tv)
        }
    }

    // ---------------- 窗口 ----------------

    /** 无障碍服务里优先用 TYPE_ACCESSIBILITY_OVERLAY，不依赖「悬浮窗」授权 */
    private fun overlayType(ctx: Context): Int =
        if (ctx is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    private fun canOverlay(ctx: Context): Boolean =
        ctx is android.accessibilityservice.AccessibilityService || Settings.canDrawOverlays(ctx)

    private fun attachContainer(ctx: Context): FrameLayout? {
        if (!canOverlay(ctx)) return null
        dismiss()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val container = FrameLayout(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(ctx),
            // 可聚焦才能输入金额，同时让面板外的触摸穿透给下面的应用
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager = wm
        layoutParams = lp
        return try {
            wm.addView(container, lp)
            root = container
            container.requestApplyInsets()
            container
        } catch (t: Exception) {
            android.util.Log.e("AutoQianji", "attachContainer failed", t)
            root = null
            null
        }
    }

    private fun attach(ctx: Context, layoutId: Int): View? {
        if (!canOverlay(ctx)) return null
        dismiss()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val view = View.inflate(themed(ctx), layoutId, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(ctx),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        return try {
            wm.addView(view, lp)
            windowManager = wm
            root = view
            view
        } catch (_: Exception) {
            root = null
            null
        }
    }

    /** 需要打字的页面把面板挪到顶部，键盘再高也盖不住 */
    private fun anchor(container: FrameLayout, atTop: Boolean) {
        val lp = layoutParams ?: return
        val wm = windowManager ?: return
        val topY = statusBarHeight(container.context)
        val newGravity = if (atTop) Gravity.TOP else Gravity.BOTTOM
        val newY = if (atTop) topY else 0
        if (lp.gravity != newGravity || lp.y != newY) {
            lp.gravity = newGravity
            lp.y = newY
            try {
                wm.updateViewLayout(container, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun statusBarHeight(ctx: Context): Int {
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else dp(ctx, 24)
    }

    private fun themed(ctx: Context): Context =
        android.view.ContextThemeWrapper(ctx, R.style.Theme_AutoQianji_Overlay)

    private fun displayOf(value: String): String {
        if (value.isBlank()) return ""
        val parts = value.split("/::/")
        return if (parts.size == 2) parts[0] + " / " + parts[1] else value
    }

    /** label 灰一点、value 纯白，一眼能分清哪个是字段名 */
    private fun setRow(tv: TextView, label: String, value: String) {
        val text = android.text.SpannableString(label + "　" + value)
        text.setSpan(
            android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(tv.context, R.color.text_secondary)
            ),
            0, label.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = text
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun vibrate(ctx: Context) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
