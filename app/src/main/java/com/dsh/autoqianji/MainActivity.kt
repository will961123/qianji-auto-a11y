package com.dsh.autoqianji

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {


    private lateinit var store: Store
    private lateinit var panelHome: View
    private lateinit var panelSettings: View
    private var selectedDay: java.util.Calendar = java.util.Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 每次启动按配置把当前任务从最近任务里隐藏
        Stability.applyHideFromRecents(this, Config.hideFromRecents(this))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        store = Store(this)
        panelHome = findViewById(R.id.panelHome)
        panelSettings = findViewById(R.id.panelSettings)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // 先把页签和可见性一次定好，再挂监听，避免"先闪首页再切过去"
        val wanted = intent?.getStringExtra("tab") ?: Config.lastTab(this)
        val startSettings = wanted == TAB_SETTINGS || wanted == "settings"
        lastTab = if (startSettings) TAB_SETTINGS else TAB_HOME
        panelHome.visibility = if (startSettings) View.GONE else View.VISIBLE
        panelSettings.visibility = if (startSettings) View.VISIBLE else View.GONE
        bottomNav.selectedItemId = if (startSettings) R.id.nav_settings else R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            val home = item.itemId == R.id.nav_home
            panelHome.visibility = if (home) View.VISIBLE else View.GONE
            panelSettings.visibility = if (home) View.GONE else View.VISIBLE
            lastTab = if (home) TAB_HOME else TAB_SETTINGS
            Config.setLastTab(this, lastTab)
            true
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<TextView>(R.id.btnManualBill).setOnClickListener {
            startActivity(Intent(this, ManualBillActivity::class.java))
        }
        findViewById<View>(R.id.btnPrevDay).setOnClickListener {
            selectedDay.add(java.util.Calendar.DAY_OF_YEAR, -1)
            renderDay()
        }
        findViewById<View>(R.id.btnNextDay).setOnClickListener {
            selectedDay.add(java.util.Calendar.DAY_OF_YEAR, 1)
            renderDay()
        }
        findViewById<TextView>(R.id.tvDayLabel).setOnClickListener { pickDay() }

        handleIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 记住设置页滚动位置，重建后不会跳回顶部
        (panelSettings as? android.widget.ScrollView)?.let {
            outState.putInt("settings_scroll", it.scrollY)
        }
        outState.putLong("selected_day", selectedDay.timeInMillis)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val y = savedInstanceState.getInt("settings_scroll", 0)
        if (y > 0) {
            val sv = panelSettings as? android.widget.ScrollView
            sv?.post { sv.scrollTo(0, y) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
        if (settingsBuilt) refreshSettingsStatus() else buildSettings()
    }

    // ---------------- 首页 ----------------


    private fun refreshHome() {
        val a11yOn = isServiceEnabled()
        val overlayOn = android.provider.Settings.canDrawOverlays(this)

        findViewById<TextView>(R.id.tvServiceState).text = when {
            !a11yOn -> "无障碍服务未开启"
            !overlayOn -> "悬浮窗权限未开启"
            else -> "运行正常"
        }

        val card = findViewById<LinearLayout>(R.id.cardService)
        val title = findViewById<TextView>(R.id.tvCardTitle)
        val hint = findViewById<TextView>(R.id.tvCardHint)
        val action = findViewById<TextView>(R.id.btnOpenAccessibility)

        // 一次只提示一个：先无障碍，再悬浮窗
        when {
            !a11yOn -> {
                card.visibility = View.VISIBLE
                title.text = "无障碍服务未开启"
                hint.text = "开启后才能读取账单详情页"
                action.text = "去开启"
                action.setOnClickListener { openAccessibilitySettings() }
            }
            !overlayOn -> {
                card.visibility = View.VISIBLE
                title.text = "悬浮窗权限未开启"
                hint.text = "自动提交到钱迹需要它，否则会被系统拦截"
                action.text = "去授权"
                action.setOnClickListener { openOverlaySettings() }
            }
            else -> card.visibility = View.GONE
        }
        renderDay()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName)
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun renderDay() {
        val start = (selectedDay.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startMs = start.timeInMillis
        val endMs = startMs + 24 * 60 * 60 * 1000L

        findViewById<TextView>(R.id.tvDayLabel).text = Common.dayLabel(start)
        val summary = store.daySummary(startMs, endMs)
        findViewById<TextView>(R.id.tvTodayAmount).text =
            String.format(java.util.Locale.US, "¥%.2f", summary.amount)
        findViewById<TextView>(R.id.tvTodayCount).text =
            if (summary.income > 0) {
                Common.summaryLine(summary.count, summary.debugCount, summary.pendingCount, summary.failedCount) +
                    " · 收入 ¥" + String.format(java.util.Locale.US, "%.2f", summary.income)
            } else {
                Common.summaryLine(summary.count, summary.debugCount, summary.pendingCount, summary.failedCount)
            }

        val container = findViewById<LinearLayout>(R.id.listEntries)
        container.removeAllViews()
        val entries = store.dayEntries(startMs, endMs)
        findViewById<EmptyState>(R.id.tvEmpty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE

        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
        for (e in entries) {
            val row = layoutInflater.inflate(R.layout.item_entry, container, false)
            // 记录时间 + 账单时间都显示，排序仍按记录时间
            row.findViewById<TextView>(R.id.tvEntryTime).text =
                timeFmt.format(java.util.Date(e.ts))
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
                billView.text = "账单 " + timeFmt.format(billTime)
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
            row.findViewById<TextView>(R.id.tvEntryAmount).text = "¥${e.amount}"
            row.findViewById<TextView>(R.id.tvEntryAmount).setTextColor(Common.amountColor(e.type))
            Common.applyStatusBadge(row.findViewById(R.id.tvEntryDebug), e.status)
            Common.onTap(row) {
                EntrySheet.show(this, e,
                    onResend = { resendToQianji(e) },
                    onDelete = { confirmDeleteEntry(e) { renderDay() } })
            }
            row.setOnLongClickListener {
                androidx.appcompat.widget.PopupMenu(this, row).apply {
                    menu.add(0, 1, 0, "发送至钱迹")
                    menu.add(0, 2, 1, "删除记录")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> resendToQianji(e)
                            2 -> confirmDeleteEntry(e) { renderDay() }
                        }
                        true
                    }
                    show()
                }
                true
            }
            row.findViewById<TextView>(R.id.tvEntryMerchant).text =
                e.merchant.ifBlank { "未知商户" }
            container.addView(row)
        }
    }

    /** 长按 → 发送至钱迹：不走队列、不判重，直接重发一次 */
    private fun resendToQianji(e: Store.DayEntry) {
        val draft = Common.draftFromLog(e, Config.book(this))
        if (draft.amount <= 0) {
            toast("金额异常，没法重发")
            return
        }
        val ok = Submitter.resendNow(this, draft, e.id)
        toast(if (ok) "已发送至钱迹" else "发送失败，检查悬浮窗权限是否开启")
        renderDay()
    }

    /** 长按记录：只删本地日志，不动钱迹里已记的账单 */
    private fun confirmDeleteEntry(e: Store.DayEntry, refresh: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(
                "删除这条记录？\n\n" +
                    "${e.merchant}  ¥${e.amount}\n\n" +
                    "只删本地记录，钱迹里已记的账单不会变。"
            )
            .setPositiveButton("删除") { _, _ ->
                store.deleteLog(e.id)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickDay() {
        DatePick.show(this, selectedDay) { picked ->
            selectedDay.set(
                picked.get(java.util.Calendar.YEAR),
                picked.get(java.util.Calendar.MONTH),
                picked.get(java.util.Calendar.DAY_OF_MONTH)
            )
            renderDay()
        }
    }

    private fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(this)

    private fun isServiceEnabled(): Boolean {
        val cn = ComponentName(this, AutoRecordService::class.java)
        val flat = cn.flattenToString()
        val short = cn.flattenToShortString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(flat, true) || it.equals(short, true) }
    }

    // ---------------- 设置页 ----------------

    private var devExpanded = false
    private var lastTab = TAB_HOME
    private var settingsBuilt = false

    private var pendingSelection: Backup.Selection? = null

    private var scrollListenerAttached = false
    private var rowA11y: View? = null
    private var rowOverlay: View? = null
    private var rowBattery: View? = null

    private fun buildSettings() {
        val record = findViewById<LinearLayout>(R.id.rowsRecord)
        val auto = findViewById<LinearLayout>(R.id.rowsAuto)
        val data = findViewById<LinearLayout>(R.id.rowsData)
        val perm = findViewById<LinearLayout>(R.id.rowsPerm)
        val rules = findViewById<LinearLayout>(R.id.rowsRules)
        val dev = findViewById<LinearLayout>(R.id.rowsDev)
        listOf(record, auto, perm, rules, data, dev).forEach { it.removeAllViews() }

        // 记账
        navRow(record, R.drawable.ic_line_cash, "手动记一笔", "补记一笔，所有字段手填") {
            startActivity(Intent(this, ManualBillActivity::class.java))
        }
        navRow(record, R.drawable.ic_line_chart, "记账记录", "按天查看，支持批量删除") {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // 自动记账
        switchRow(
            auto, R.drawable.ic_line_bolt, "启用自动记账",
            sub = "打开账单详情页时自动记账",
            get = { Config.enabled(this) },
            onChange = { Config.setEnabled(this, it) }
        )
        switchRow(
            auto, R.drawable.ic_line_note, "提交前确认",
            sub = "先弹窗核对，再提交到钱迹",
            get = { Config.confirmBeforeSubmit(this) },
            onChange = { Config.setConfirmBeforeSubmit(this, it) }
        )
        rowA11y = navRow(
            perm, R.drawable.ic_line_phone, "系统无障碍设置",
            if (isServiceEnabled()) "已开启" else "未开启",
            showArrow = false
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        rowOverlay = navRow(
            perm, R.drawable.ic_line_layers, "悬浮窗权限",
            if (canDrawOverlays()) "已授权，可在其他应用上层显示" else "未授权，账单提交会被系统拦截",
            showArrow = false
        ) {
            if (canDrawOverlays()) {
                toast("已授权")
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
        navRow(perm, R.drawable.ic_line_power, "系统优化", "提升自动记账的稳定性") {
            startActivity(Intent(this, StabilityActivity::class.java))
        }
        navRow(rules, R.drawable.ic_line_card, "资产管理", "记账用的账户，资产匹配规则依赖它") {
            startActivity(Intent(this, LookupActivity::class.java).putExtra(LookupActivity.EXTRA_KIND, Store.KIND_ASSET))
        }
navRow(rules, R.drawable.ic_line_tag, "分类管理", "记账用的分类，分类匹配规则依赖它") {
            startActivity(Intent(this, LookupActivity::class.java).putExtra(LookupActivity.EXTRA_KIND, Store.KIND_CATEGORY))
        }
navRow(rules, R.drawable.ic_line_book, "账本管理", "记账用的账本") {
            startActivity(Intent(this, LookupActivity::class.java).putExtra(LookupActivity.EXTRA_KIND, Store.KIND_BOOK))
        }
navRow(rules, R.drawable.ic_line_list, "分类匹配规则", "关键词匹配，如 瑞幸 → 咖啡") {
            startActivity(
                Intent(this, RulesActivity::class.java)
                    .putExtra(RulesActivity.EXTRA_KIND, Store.RULE_CATEGORY)
            )
        }
        navRow(rules, R.drawable.ic_line_wallet, "资产匹配规则", "付款方式 → 钱迹账户") {
            startActivity(
                Intent(this, RulesActivity::class.java)
                    .putExtra(RulesActivity.EXTRA_KIND, Store.RULE_ASSET)
            )
        }
        navRow(rules, R.drawable.ic_line_sliders, "默认值", "识别不到时使用的资产、分类、账本") {
            startActivity(Intent(this, DefaultsActivity::class.java))
        }

        // 钱迹数据
        navRow(data, R.drawable.ic_line_import, "从钱迹导入", "读取钱迹导出的文件，写入本应用", showArrow = false) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_IMPORT
            )
        }
        navRow(data, R.drawable.ic_line_layers, "导出数据", "选择要备份的内容，保存为 JSON 文件") {
            chooseParts("选择要导出的内容", Backup.Selection.defaultChecked()) { sel ->
                pendingSelection = sel
                val name = "autoqianji-" + java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmm", java.util.Locale.US
                ).format(java.util.Date()) + ".json"
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, name)
                    },
                    REQ_EXPORT
                )
            }
        }
        navRow(data, R.drawable.ic_line_import, "导入数据", "从备份文件恢复，可只覆盖部分内容") {
            chooseParts("选择要导入的内容", Backup.Selection.defaultChecked()) { sel ->
                pendingSelection = sel
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQ_IMPORT_BACKUP
                )
            }
        }
                        
        settingsBuilt = true
        val scroll = panelSettings as? android.widget.ScrollView
        if (scroll != null) {
            if (!scrollListenerAttached) {
                scrollListenerAttached = true
                scroll.setOnScrollChangeListener { _, _, y, _, _ ->
                    Config.setSettingsScroll(this, y)
                }
            }
            val saved = Config.settingsScroll(this)
            if (saved > 0) scroll.post { scroll.scrollTo(0, saved) }
        }

        // 开发者选项（默认折叠）
        findViewById<View>(R.id.rowsDev).visibility = if (devExpanded) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvDevToggle).text = if (devExpanded) "收起" else "展开"
        findViewById<View>(R.id.devHeader).setOnClickListener {
            devExpanded = !devExpanded
            buildSettings()
        }
        if (devExpanded) {
            switchRow(
                dev, R.drawable.ic_line_flask, "仅记录不提交",
                sub = "不发送给钱迹，只在本地留记录" ,
                get = { Config.dryRun(this) },
                onChange = { Config.setDryRun(this, it) }
            )
            navRow(
                dev, R.drawable.ic_line_trash, "重置数据库", "清空所有数据，请谨慎操作",
                titleColor = 0xFFFF6B6B.toInt(),
                showArrow = false
            ) {
                val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("重置数据库")
                    .setMessage("会清空所有本地数据（资产、分类、账本、日志、分类规则），之后需要重新从钱迹导入。")
                    .setPositiveButton("继续") { _, _ -> confirmResetSecondTime() }
                    .setNegativeButton("取消", null)
                    .create()
                dlg.show()
                dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    ?.setTextColor(0xFFFF6B6B.toInt())
            }
        }

        findViewById<TextView>(R.id.tvAbout).setOnClickListener { showAbout() }
    }


    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("钱迹无障碍记账插件")
            .setMessage(
                "版本 0.1.0\n\n" +
                    "使用 Android 无障碍服务读取支付结果页面，通过钱迹公开的 " +
                    "qianji://publicapi/addbill 接口记账。\n\n" +
                    "不申请网络权限，所有识别在本地完成。\n\n" +
                    "本应用为个人自用工具，与钱迹官方无任何关联。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun navRow(
        container: LinearLayout,
        icon: Int,
        title: String,
        sub: String? = null,
        titleColor: Int? = null,
        showArrow: Boolean = true,
        onClick: () -> Unit
    ): View {
        val v = layoutInflater.inflate(R.layout.item_setting, container, false)
        val iconView = v.findViewById<ImageView>(R.id.ivSettingIcon)
        iconView.setImageResource(icon)
        val titleView = v.findViewById<TextView>(R.id.tvSettingTitle)
        titleView.text = title
        val subView = v.findViewById<TextView>(R.id.tvSettingSub)
        if (sub.isNullOrEmpty()) {
            subView.visibility = View.GONE
        } else {
            subView.visibility = View.VISIBLE
            subView.text = sub
        }
        if (titleColor != null) {
            // 危险操作整行染成警告色：标题、图标、箭头、说明
            titleView.setTextColor(titleColor)
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(titleColor)
            subView.setTextColor(titleColor)
            v.findViewById<ImageView>(R.id.ivSettingArrow)?.imageTintList =
                android.content.res.ColorStateList.valueOf(titleColor)
        }
        if (!showArrow) {
            v.findViewById<ImageView>(R.id.ivSettingArrow)?.visibility = View.GONE
        }
        v.setOnClickListener { onClick() }
        container.addView(v)
        return v
    }

    private fun switchRow(
        container: LinearLayout,
        icon: Int,
        title: String,
        sub: String? = null,
        get: () -> Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val v = layoutInflater.inflate(R.layout.item_setting, container, false)
        v.findViewById<ImageView>(R.id.ivSettingIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tvSettingTitle).text = title
        val subView = v.findViewById<TextView>(R.id.tvSettingSub)
        if (sub.isNullOrEmpty()) {
            subView.visibility = View.GONE
        } else {
            subView.visibility = View.VISIBLE
            subView.text = sub
        }

        val arrow = v.findViewById<ImageView>(R.id.ivSettingArrow)
        (arrow.parent as ViewGroup).removeView(arrow)

        val sw = SwitchCompat(this).apply { isChecked = get() }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        (v as ViewGroup).addView(sw)
        v.setOnClickListener { sw.toggle() }
        container.addView(v)
    }

    // ---------------- 导入 ----------------


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("tab") == "settings") {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_settings
        }
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: android.net.Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) doImport(uri)
    }

    @Deprecated("兼容旧方式，避免额外依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT -> doImport(uri)
            REQ_EXPORT -> doExport(uri)
            REQ_IMPORT_BACKUP -> doImportBackup(uri)
        }
    }

    /** 勾选要处理的几块数据 */
    private fun chooseParts(
        title: String,
        initial: BooleanArray,
        onOk: (Backup.Selection) -> Unit
    ) {
        val checked = initial.copyOf()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(Backup.Selection.LABELS.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("下一步") { _, _ ->
                val sel = Backup.Selection.of(checked)
                if (sel.isEmpty()) toast("至少选一项") else onOk(sel)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(uri: android.net.Uri) {
        val sel = pendingSelection
        if (sel == null) {
            toast("没有选择导出内容")
            return
        }
        try {
            val text = Backup.export(this, store, sel)
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            toast("已导出 ${text.length} 字节")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    private fun doImportBackup(uri: android.net.Uri) {
        val sel = pendingSelection
        if (sel == null) {
            toast("没有选择导入内容")
            return
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                toast("文件是空的")
                return
            }
            val summary = Backup.import(this, store, String(bytes, Charsets.UTF_8), sel)
            refreshHome()
            buildSettings()
            toast("已导入：$summary")
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private fun doImport(uri: android.net.Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                toast("文件是空的")
                return
            }
            val result = Importer.parse(bytes)
            if (result.assets.isEmpty() && result.categories.isEmpty()) {
                toast("没解析到资产/分类，确认选的是钱迹导出的文件")
                return
            }
            store.replaceLookups(result.assets, result.categories, result.books)
            Config.setLastImportAt(this, System.currentTimeMillis())
            refreshHome()
            buildSettings()
            toast("导入资产 ${result.assets.size}、分类 ${result.categories.size}、账本 ${result.books.size}")
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private fun setSub(row: View?, text: String) {
        row?.findViewById<TextView>(R.id.tvSettingSub)?.text = text
    }

    private fun refreshSettingsStatus() {
        setSub(rowA11y, if (isServiceEnabled()) "已开启" else "未开启")
        setSub(rowOverlay, if (canDrawOverlays()) "已授权" else "未授权，提交会被拦截")
        setSub(
            rowBattery,
            if (Stability.isIgnoringBattery(this)) "已忽略电池优化" else "避免省电策略限制后台"
        )
    }

    /** 二次确认：清空数据不可撤销 */
    private fun confirmResetSecondTime() {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认重置？")
            .setMessage("所有本地数据将被永久删除，且无法恢复。\n\n确定要继续吗？")
            .setPositiveButton("确认重置") { _, _ ->
                store.resetAll()
                refreshHome()
                buildSettings()
                toast("已重置")
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(0xFFFF6B6B.toInt())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_HOME = "home"
        private const val TAB_SETTINGS = "settings"
        private const val REQ_IMPORT = 1001
    private const val REQ_EXPORT = 1002
    private const val REQ_IMPORT_BACKUP = 1003
    }
}
