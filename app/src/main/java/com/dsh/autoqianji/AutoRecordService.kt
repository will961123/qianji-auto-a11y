package com.dsh.autoqianji

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoRecordService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveView: android.view.View? = null
    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val probeBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastProbe = HashMap<String, Long>()
    private val prompted = HashMap<String, Long>()
    private val dismissedPages = LinkedHashSet<String>()
    private val probeRetries = HashMap<String, Int>()

    /** 每个包上一次解析的指纹，连续两次一致才弹窗（避免页面还在渲染时抓半截） */
    private val lastSignature = HashMap<String, String>()

    /** 已提交但日志还没落库的短暂窗口，防止同一次支付被连续触发多次 */
    private val pending = HashMap<String, Long>()

    /** 最近一个「真实应用」的包名：点通知栏按钮时前台是 SystemUI，得靠它找回原页面 */
    @Volatile
    private var lastAppPkg: String? = null

    @Volatile
    private var lastResult: String? = null

    private val ignoredPkgs = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.miui.screenshot",
        "com.android.screenshot",
        // 小米的输入法、权限弹窗等系统窗口，不算「离开账单页」
        "com.miui.securityinputmethod",
        "android"
    )

    /** 需要识别的包：内置的三个 + pay_rules.json 里配置的所有 App */
    private val supportedPkgs: Set<String> by lazy {
        Pkg.ALL + PayRules.supportedPackages(this)
    }

    /** 最近一次看到的「账单页」快照：通知栏按钮在 SystemUI 前台时靠它兜底 */
    private data class Snapshot(val pkg: String, val nodes: List<NodeMeta>, val at: Long)

    @Volatile
    private var lastBillPage: Snapshot? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DEBUG_DUMP -> dumpCurrent("broadcast")
                ACTION_FORCE_RECORD -> forceRecordDelayed(0)
                ACTION_TOGGLE -> toggleEnabled()
                ACTION_IMPORT_TEST -> importTest()
            }
        }
    }

    override fun onServiceConnected() {
        dismissedPages.clear()
        lastSignature.clear()
        probeRetries.clear()
        super.onServiceConnected()
        instance = this
        attachKeepAliveOverlay()
        Log.i(TAG, "service connected")
        try {
            android.widget.Toast.makeText(this, "自动记账已启动", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
        registerCommands()
        handler.postDelayed({ captureSnapshot() }, 1200L)
        // 刚连接时系统还没推过窗口事件，主动读几次，把交互连接激活
        handler.postDelayed({ schedulePoll() }, 600L)
        handler.postDelayed({ schedulePoll() }, 2000L)
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, 1500L)
    }

    /**
     * 挂着 1px 的 TYPE_ACCESSIBILITY_OVERLAY 窗口（GKD 也是这么干的）：
     * 有了自己的可见窗口，系统/ROM 不会把本进程当成纯后台进程冻结，
     * 无障碍的窗口连接也更稳定。
     */
    private fun attachKeepAliveOverlay() {
        if (keepAliveView != null) return
        try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return
            val v = android.view.View(this)
            val lp = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSPARENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
            }
            wm.addView(v, lp)
            keepAliveView = v
            Log.i(TAG, "keepalive overlay attached")
        } catch (e: Exception) {
            Log.w(TAG, "keepalive overlay failed: ${e.message}")
        }
    }

    private fun detachKeepAliveOverlay() {
        val v = keepAliveView ?: return
        keepAliveView = null
        try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            wm?.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun registerCommands() {
        val filter = IntentFilter().apply {
            addAction(ACTION_DEBUG_DUMP)
            addAction(ACTION_FORCE_RECORD)
            addAction(ACTION_TOGGLE)
            addAction(ACTION_IMPORT_TEST)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in ignoredPkgs && pkg != packageName) {
            // 只有切到「别的应用」才说明离开了账单页；
            // 自己（悬浮窗）、桌面、通知栏、截屏预览都不算离开。
            // 换了前台应用就说明离开了上一页，取消抑制随之清理：
            // 重新进入同一笔账单会再次弹窗，停留原页则不重复弹
            if (lastAppPkg != null && lastAppPkg != pkg && !OverlayBill.isShowing()) {
                dismissedPages.clear()
                lastSignature.clear()
            }
            lastAppPkg = pkg
        }
        if (pkg in supportedPkgs) {
            Log.d(TAG, "event pkg=$pkg type=${e.eventType} class=${e.className} src=${e.source != null} enabled=${Config.enabled(this)}")
        }
        if (pkg !in ignoredPkgs && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed({ captureSnapshot() }, 500L)
        }
        if (!Config.enabled(this)) return
        if (pkg !in supportedPkgs) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 有些页面事件不带 source，过滤掉会漏掉真正的首次渲染
        val delays = if (pkg == Pkg.ALIPAY) {
            longArrayOf(160L, 450L, 900L, 1600L, 2500L)
        } else {
            longArrayOf(150L, 600L, 1500L)
        }
        for (delay in delays) {
            handler.postDelayed({ probe(pkg) }, delay)
        }
    }

    /** 无障碍刚连上时 rootInActiveWindow 可能为空，退回遍历窗口找目标包 */


    private fun activeRoot(pkg: String): android.view.accessibility.AccessibilityNodeInfo? {
        // 只有当前窗口确实属于目标应用才直接用；
        // 我们自己的浮窗也会成为 active window，不能拿它当账单页
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == pkg) return root
        }
        return try {
            val all = windows ?: return null
            all.firstOrNull { it.isActive && it.root?.packageName?.toString() == pkg }?.root
                ?: all.firstOrNull { it.root?.packageName?.toString() == pkg }?.root
        } catch (e: Exception) {
            null
        }
    }

    /** 轮询兜底：事件不稳定时也能发现账单页 */
    private var blindTicks = 0
    private var healAttempts = 0
    private var lastHealAt = 0L

    private val pollTask = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm?.isInteractive == true && Config.enabled(this@AutoRecordService)) {
                    schedulePoll()
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 2500L)
        }
    }

    /**
     * 读节点一律放到工作线程。
     * 支付宝的 WebView 页面偶尔会让 binder 调用卡住，卡在主线里会让整个无障碍服务失联
     * （表现就是「必须下拉通知栏才恢复」）。
     */
    private fun schedulePoll() {
        if (!probeBusy.compareAndSet(false, true)) {
            Log.d(TAG, "poll skipped: previous probe still running")
            return
        }
        probeExecutor.execute {
            try {
                var pkg = rootInActiveWindow?.packageName?.toString()
                if (pkg == null) {
                    pkg = windows?.firstOrNull {
                        it.root?.packageName?.toString()?.let { p -> p in supportedPkgs } == true
                    }?.root?.packageName?.toString()
                }
                if (pkg != null && pkg in supportedPkgs) {
                    blindTicks = 0
                    probeNow(pkg)
                } else {
                    blindTicks++
                    if (blindTicks % 4 == 1) {
                        Log.d(TAG, "poll blind root=${rootInActiveWindow != null} windows=${windows?.size ?: 0}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "poll failed: $t")
            } finally {
                probeBusy.set(false)
            }
        }
    }


    private fun probe(pkg: String) {
        if (!probeBusy.compareAndSet(false, true)) return
        probeExecutor.execute {
            try {
                probeNow(pkg)
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: $t")
            } finally {
                probeBusy.set(false)
            }
        }
    }

    /** 真正读节点的地方，只会在工作线程上执行 */
    private fun probeNow(pkg: String) {
        val now = System.currentTimeMillis()
        val last = lastProbe[pkg] ?: 0L
        if (now - last < 120L) return
        lastProbe[pkg] = now

        val root = activeRoot(pkg)
        if (root == null) {
            Log.d(TAG, "probe pkg=$pkg root=null")
            // 刚开启无障碍时还没收到过窗口事件，rootInActiveWindow 还是空的，隔一会儿再试
            val key = "root|" + (now / 1000)
            if ((probeRetries[key] ?: 0) < 4) {
                probeRetries[key] = (probeRetries[key] ?: 0) + 1
                handler.postDelayed({ probe(pkg) }, 500L)
            }
            return
        }
        val rootPkg = root.packageName?.toString()
        // 有些 App（抖音等）把内容画在另一个窗口里，把同包名的所有窗口都读一遍
        val nodes = collectNodes(pkg, root)
        Log.d(TAG, "probe pkg=$pkg rootPkg=$rootPkg nodes=${nodes.size} ${NodeScan.debugSummary(this, pkg, nodes)}")
        if (NodeScan.isBillPage(this, pkg, nodes)) {
            lastBillPage = Snapshot(pkg, nodes, now)
        }

        val draft = parseDraft(pkg, nodes)
        if (draft == null) {
            Log.d(TAG, "probe no-draft texts=${NodeScan.debugTexts(nodes)}")
            // 看起来像详情页却没匹配到规则：打一条完整文本，按它补规则
            if (NodeScan.looksLikeDetail(nodes)) {
                val unknownKey = "unknown|" + pkg + "|" + (now / 5000)
                if ((probeRetries[unknownKey] ?: 0) == 0) {
                    probeRetries[unknownKey] = 1
                    Log.i(TAG, "unknown-detail pkg=$pkg texts=${NodeScan.debugTexts(nodes, 60)}")
                }
            }
            // 页面还在渲染时读不到金额/时间，隔一会儿再读几次
            if (NodeScan.isBillPage(this, pkg, nodes)) {
                val retryKey = pkg + "|" + (now / 3000)
                if ((probeRetries[retryKey] ?: 0) < 3) {
                    probeRetries[retryKey] = (probeRetries[retryKey] ?: 0) + 1
                    handler.postDelayed({ probe(pkg) }, 1200L)
                }
            }
            return
        }
        Log.i(
            TAG,
            "draft amount=${draft.amount} net=${draft.logAmount} merchant=${draft.merchant} type=${draft.type} cate=${draft.catename} " +
                "account=${draft.account} coupon=${draft.coupon} time=${draft.time} remark=${draft.remark} " +
                "method=${draft.paymentMethod}"
        )

        // 解析结果可疑（商户没取到 / 商户等于付款方式）就先别弹，等页面渲染完整
        val sane = draft.merchant.isNotBlank() &&
            draft.merchant != "未知商户" &&
            draft.merchant != draft.paymentMethod &&
            draft.merchant.length >= 2 &&
            draft.remark.contains(draft.merchant)
        if (!sane) {
            Log.i(TAG, "draft not sane, retry later: ${draft.merchant} / ${draft.paymentMethod}")
            handler.postDelayed({ probe(pkg) }, 700L)
            return
        }

        // 连续两次解析一致才认为页面稳定
        val signature = "${draft.amount}|${draft.merchant}|${draft.account}|${draft.catename}|${draft.time}"
        if (lastSignature[pkg] != signature) {
            lastSignature[pkg] = signature
            Log.i(TAG, "draft unstable, wait one more probe")
            handler.postDelayed({ probe(pkg) }, 600L)
            return
        }

        if (Config.confirmBeforeSubmit(this)) {
            val pageKey = pkg + "|" + root.windowId
            handler.post { promptForConfirm(draft, pageKey) }
        } else {
            handler.post { dispatch(draft) }
        }
    }

    /** 合并同包名所有窗口的节点，去重后返回 */
    private fun collectNodes(pkg: String, root: android.view.accessibility.AccessibilityNodeInfo): List<NodeMeta> {
        val merged = LinkedHashMap<String, NodeMeta>()
        fun addAll(from: android.view.accessibility.AccessibilityNodeInfo) {
            NodeScan.collect(from).forEach { node ->
                val key = node.text + "|" + node.top + "|" + node.left
                merged.putIfAbsent(key, node)
            }
        }
        addAll(root)
        runCatching {
            windows?.forEach { w ->
                val r = w.root
                if (r != null && r.packageName?.toString() == pkg) addAll(r)
            }
        }
        return merged.values.toList()
    }

    private fun parseDraft(pkg: String, nodes: List<NodeMeta>): Draft? {
        val assets = try {
            Store(this).assets()
        } catch (e: Exception) {
            emptyList()
        }
        return NodeScan.parse(this, pkg, nodes, assets, Config.defaultAccount(this), Config.book(this))
    }

    /**
     * 通知栏「补记当前页」：忽略 15 分钟新鲜度限制，手动把当前页面记一笔。
     * 点按钮时前台窗口是 SystemUI（通知中心还没收起），所以延迟几次再读，
     * 等通知中心收起来、下面的应用回到前台。
     */
    /** 同样放进工作线程：这里是每次窗口变化都会跑的全量遍历 */
    private fun captureSnapshot() {
        if (!probeBusy.compareAndSet(false, true)) return
        probeExecutor.execute {
            try {
                val root = rootInActiveWindow ?: return@execute
                val pkg = root.packageName?.toString() ?: return@execute
                if (pkg in ignoredPkgs) return@execute
                val nodes = NodeScan.collect(root)
                if (NodeScan.isBillPage(this, pkg, nodes)) {
                    lastBillPage = Snapshot(pkg, nodes, System.currentTimeMillis())
                    Log.d(TAG, "snapshot cached pkg=$pkg nodes=${nodes.size}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed: $t")
            } finally {
                probeBusy.set(false)
            }
        }
    }

    private fun forceRecordDelayed(attempt: Int) {
        handler.postDelayed({
            val root = try { rootInActiveWindow } catch (_: Throwable) { null }
            val pkg = root?.packageName?.toString()
            if (root != null && pkg != null && pkg !in ignoredPkgs) {
                val captured = NodeScan.collect(root)
                handler.post { forceRecordFrom(pkg, captured) }
                return@postDelayed
            }
            if (attempt < 3) {
                forceRecordDelayed(attempt + 1)
                return@postDelayed
            }
            // 通知中心还开着，用刚看到的账单页快照兜底
            val snap = lastBillPage
            if (snap != null && System.currentTimeMillis() - snap.at < 5 * 60_000L) {
                Log.i(TAG, "forceRecord use snapshot pkg=${snap.pkg} age=${System.currentTimeMillis() - snap.at}ms")
                forceRecordFrom(snap.pkg, snap.nodes)
            } else {
                Log.i(TAG, "forceRecord gave up, last pkg=$pkg")
                showResult("没读到页面，先把账单页打开再点")
            }
        }, if (attempt == 0) 600L else 500L)
    }

    private fun forceRecordFrom(pkg: String, nodes: List<NodeMeta>) {
        Log.i(TAG, "forceRecord pkg=$pkg nodes=${nodes.size} ${NodeScan.debugSummary(this, pkg, nodes)}")
        val draft = parseDraft(pkg, nodes)
        if (draft == null) {
            Log.i(TAG, "forceRecord no-draft pkg=$pkg texts=${NodeScan.debugTexts(nodes)}")
            showResult("当前页面没识别到账单")
            return
        }
        if (Config.confirmBeforeSubmit(this)) {
            val pageKey = pkg + "|force"
            handler.post { promptForConfirm(draft, pageKey) }
        } else {
            handler.post { dispatch(draft) }
        }
    }

    /** 开发用：把 test.xlsx 放进应用私有目录后，用广播跑一遍导入解析 */
    private fun importTest() {
        try {
            val f = java.io.File(getExternalFilesDir(null), "test.xlsx")
            if (!f.exists()) {
                Log.i(TAG, "importTest no file: ${f.absolutePath}")
                return
            }
            val r = Importer.parse(f.readBytes())
            Store(this).replaceLookups(r.assets, r.categories, r.books)
            Log.i(TAG, "importTest assets=${r.assets.size} ${r.assets.take(12)}")
            Log.i(TAG, "importTest cats=${r.categories.size} ${r.categories.take(12)}")
            Log.i(TAG, "importTest books=${r.books.size} ${r.books.take(5)}")
        } catch (e: Exception) {
            Log.e(TAG, "importTest fail: ${e.message}")
        }
    }

    private fun showResult(text: String) {
        lastResult = text
        try {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    /**
     * 弹悬浮窗让用户核对后再提交。
     * 同一个页面（同一窗口）只弹一次：用户关掉后不再骚扰，
     * 直到重新进入这个页面（窗口 id 变了）才会再弹。
     */
    private fun promptForConfirm(draft: Draft, pageKey: String) {
        val key = draft.pkg + "|" + String.format(java.util.Locale.US, "%.2f", draft.amount) +
            "|" + draft.merchant + "|" + (draft.time ?: "")
        val now = System.currentTimeMillis()
        // 同页面可能翻到别的账单，取消抑制要精确到这一笔
        val dismissKey = pageKey + "|" + String.format(java.util.Locale.US, "%.2f", draft.amount) +
            "|" + (draft.time ?: "")
        if (dismissedPages.contains(dismissKey)) {
            Log.i(TAG, "prompt skip: dismissed $dismissKey")
            return
        }
        if (now - (prompted[key] ?: 0L) < 3_000L) {
            Log.i(TAG, "prompt skip: cooldown")
            return
        }
        if (OverlayBill.isShowing()) {
            Log.i(TAG, "prompt skip: overlay showing")
            return
        }
        prompted[key] = now
        prompted.entries.removeAll { now - it.value > 30 * 60_000L }
        Log.i(TAG, "prompt show key=$key")

        val markDismissed = {
            dismissedPages.add(dismissKey)
            while (dismissedPages.size > 40) {
                val first = dismissedPages.firstOrNull() ?: break
                dismissedPages.remove(first)
            }
        }

        val alreadyLogged = try {
            val store = Store(this)
            val amountText = String.format(java.util.Locale.US, "%.2f", draft.amount)
            if (draft.time != null) {
                store.hasLoggedBill(draft.pkg, amountText, draft.merchant, draft.time!!)
            } else {
                store.hasRecentLog(draft.pkg, amountText, draft.merchant, now - 60_000L)
            }
        } catch (e: Exception) {
            false
        }

        if (alreadyLogged) {
            // 先确认再展开完整内容：避免重复记账，也避免以后接模型时白调一次
            OverlayBill.showDuplicate(
                this,
                draft,
                onContinue = {
                    OverlayBill.showSheet(
                        this,
                        draft,
                        alreadyLogged = true,
                        onDismiss = markDismissed,
                        onSubmit = { edited ->
                            markDismissed()
                            forceSubmit(edited)
                        }
                    )
                },
                onCancel = markDismissed
            )
        } else {
            OverlayBill.showSheet(
                this,
                draft,
                alreadyLogged = false,
                onDismiss = markDismissed,
                onSubmit = { edited ->
                    markDismissed()
                    dispatch(edited)
                }
            )
        }
    }

    /** 用户在浮窗上对「已添加」的账单仍然点提交：不再判重，直接再记一笔 */
    private fun forceSubmit(draft: Draft) {
        val ok = Submitter.submitNow(this, draft)
        showResult(if (ok) "已重新提交 ¥" else "提交失败")
    }

    private fun dispatch(draft: Draft) {
        Log.d(TAG, "draft amount=${draft.amount} merchant=${draft.merchant} cate=${draft.catename} type=${draft.type} account=${draft.account} method=${draft.paymentMethod} time=${draft.time}")
        val now = System.currentTimeMillis()
        val amountText = String.format(java.util.Locale.US, "%.2f", draft.amount)
        val windowMs = if (draft.time != null) 20 * 60_000L else 60_000L

        // 判重以「记账记录」为准：
        // 有账单时间 → 同一笔账单只记一次（跨天浏览历史也不会重复）
        // 没有账单时间 → 退回按时间窗去重
        val logged = try {
            val store = Store(this)
            if (draft.time != null) {
                store.hasLoggedBill(draft.pkg, amountText, draft.merchant, draft.time!!)
            } else {
                store.hasRecentLog(draft.pkg, amountText, draft.merchant, now - windowMs)
            }
        } catch (e: Exception) {
            false
        }
        if (logged) {
            Log.d(TAG, "skip, already in logs: $amountText ${draft.merchant}")
            showResult("这笔已经记过了")
            return
        }

        // 提交有 3 秒排队，这期间同一个页面会重复触发，用内存里的 pending 兜一下
        val pendingKey = "${draft.pkg}|$amountText|${draft.merchant}"
        if (now - (pending[pendingKey] ?: 0L) < 15_000L) {
            Log.d(TAG, "skip, still pending: $pendingKey")
            return
        }
        pending[pendingKey] = now
        pending.entries.removeAll { now - it.value > 60_000L }

        Submitter.submit(this, draft)
        showResult("已提交 ¥$amountText ${draft.merchant}")
    }

    private fun toggleEnabled() {
        val next = !Config.enabled(this)
        Config.setEnabled(this, next)
        Log.i(TAG, "toggle enabled=$next")

    }


    /** 调试用：把当前界面的节点文本打到 logcat，方便远程定位识别问题。 */
    private fun dumpCurrent(reason: String) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.i(TAG, "DUMP($reason) root=null")
            return
        }
        val nodes = NodeScan.collect(root)
        Log.i(TAG, "DUMP($reason) pkg=${root.packageName} nodes=${nodes.size} ${NodeScan.debugSummary(this, root.packageName?.toString().orEmpty(), nodes)}")
        Log.i(TAG, "DUMP($reason) texts=${NodeScan.debugTexts(nodes, 60)}")
    }


    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        detachKeepAliveOverlay()
        if (instance === this) instance = null

        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "AutoQianji"
        const val ACTION_DEBUG_DUMP = "com.dsh.autoqianji.DEBUG_DUMP"
        const val ACTION_FORCE_RECORD = "com.dsh.autoqianji.FORCE_RECORD"
        const val ACTION_TOGGLE = "com.dsh.autoqianji.TOGGLE"
        const val ACTION_IMPORT_TEST = "com.dsh.autoqianji.IMPORT_TEST"
        private const val CHANNEL_ID = "auto_record_v4"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: AutoRecordService? = null
            private set
    }
}
