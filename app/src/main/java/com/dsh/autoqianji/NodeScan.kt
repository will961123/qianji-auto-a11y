package com.dsh.autoqianji

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Locale

object NodeScan {

    // 金额允许 1~2 位小数：支付宝账单里会出现「支出16.5元」这种写法
    private val SYMBOL_AMOUNT = Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val YUAN_AMOUNT = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元""")
    private val PLAIN_AMOUNT = Regex("""^[+-]?([0-9][0-9,]*\.[0-9]{2})$""")
    /** 2026-10-18 11:25:08 */
    private val TIME_RE = Regex("""(20\d{2})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")

    /** 2026年10月8日 19:17:18（微信支付凭证） */
    private val TIME_CN = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2}):(\d{2})""")

    /** 只补记最近 15 分钟内的详情页，避免翻旧账单时重复记账 */

    private val BLOCK_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成", "已支付",
        "收款成功", "已收款", "转账成功", "退款成功", "到账成功", "详情", "完成",
        "返回", "关闭", "确定", "取消", "余额", "零钱", "银行卡", "账单",
        "支付方式", "当前状态", "交易单号", "商户单号", "创建时间", "付款时间",
        "收款方", "付款方", "订单号", "查看更多", "完成时间", "支付时间", "优惠"
    )

    /** 详情页里的标签类文本，不能当商户名 */
    private val DETAIL_LABELS = listOf(
        "账单详情", "云闪付交易详情", "全部账单", "账单管理", "账单分类", "账单服务",
        "交易详情", "交易服务", "支付时间", "创建时间", "付款方式", "支付方式", "收款方式",
        "当前状态", "交易状态", "订单时间", "订单金额", "转账金额", "支付奖励", "计入收支",
        "开关", "更多", "管理自动扣款", "管理扣费服务", "请选择", "备注", "添加", "标签",
        "我的消费图鉴", "申请电子回单", "对此订单有疑问", "立即领取", "为您推荐", "代付",
        "自动扣款", "免密支付", "扣款", "交易成功", "已退款", "退款", "收支", "收入", "支出",
        "关闭", "返回", "完成", "复制", "分享", "删除", "查看", "收起", "展开"
    )

    private const val MIN_AMOUNT_HEIGHT = 24

    private data class Candidate(val node: NodeMeta, val value: Double, val score: Int)
    private data class TimeHit(val millis: Long, val text: String, val index: Int)

    fun collect(root: AccessibilityNodeInfo): List<NodeMeta> {
        val out = ArrayList<NodeMeta>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        val rect = Rect()
        while (queue.isNotEmpty() && guard < 2500) {
            guard++
            val n = queue.removeFirst()
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val s = if (t.isNotEmpty()) t else d
            if (s.isNotEmpty()) {
                n.getBoundsInScreen(rect)
                out.add(NodeMeta(s, rect.top, rect.bottom, rect.left, rect.right, n.className?.toString().orEmpty()))
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    fun parse(
        ctx: Context,
        pkg: String,
        nodes: List<NodeMeta>,
        assets: List<String>,
        fallbackAccount: String,
        book: String
    ): Draft? {
        if (nodes.isEmpty()) return null
        val all = buildString {
            for (n in nodes) {
                append(n.text)
                append(' ')
            }
        }

        // 同个 App 有多种详情页，按页面特征挑最匹配的那套规则
        val profile = PayRules.pick(ctx, pkg, all) ?: return null

        // 详情卡片可能盖在别的页面上（京东钱包等），先把范围收窄到卡片内
        val scoped = scopeNodes(nodes, profile)
        val scopedAll = buildString {
            for (n in scoped) { append(n.text); append(' ') }
        }

        // 不限制账单新旧：老账单点开也能记，重复打开由「账单时间」判重挡住
        val timeText = findTime(scoped, profile.timeLabels)?.text
        if (timeText == null && !profile.timeOptional) return null

        val labelAmount = profile.amountLabels.takeIf { it.isNotEmpty() }
            ?.let { labels -> labelValue(scoped, labels)?.let { firstMoney(it) } }
        val best = scoped.mapNotNull { candidate(it) }.maxByOrNull { it.score }
        val amount = labelAmount ?: best?.value ?: return null
        val amountNode = best?.node ?: scoped.firstOrNull { candidate(it)?.value == amount }
            ?: return null
        if (amount <= 0.0 || amount > 1_000_000.0) return null

        val fieldLabels = (
            profile.merchantLabels + profile.descLabels + profile.timeLabels +
                profile.methodLabels + profile.categoryLabels + profile.couponLabels
            ).distinct()
        val merchant = merchantFromLabel(scoped, profile.merchantLabels)
            ?: merchantBySuffix(scoped, profile.merchantSuffixes)
            ?: merchantAboveAmount(scoped, amountNode, fieldLabels)
            ?: merchantFromDetail(scoped, amountNode, fieldLabels)
        val amountText = amountNode.text.trim()
        val type = if (profile.fixedType >= 0) profile.fixedType else when {
            amountText.contains("支出") -> 0
            amountText.contains("收入") || amountText.contains("收款") -> 1
            amountText.startsWith("-") -> 0
            amountText.startsWith("+") -> 1
            else -> detectType(scoped, scopedAll, profile)
        }
        val store = Store(ctx)
        // 优先级：
        // 1. 关键词规则命中商户名
        // 2. 关键词规则命中页面全文（能覆盖「亲情卡消费」这类付款方式提示）
        // 3. 页面自带分类（支付宝「账单分类」）
        // 4. 默认分类 → 其它
        val pageCate = payCategory(scoped, profile.categoryLabels)
        val known = store.categories()
        val ruleCate = profile.defaultCategory.takeIf { target ->
            target.isNotBlank() && if (target.contains("/::/")) {
                target.split("/::/").all { known.contains(it) }
            } else {
                known.contains(target)
            }
        }
        val rawCate = store.matchCategory(merchant)
            ?: store.matchCategory(scopedAll)
            ?: pageCate?.let { Builtin.resolvePageCategory(it, known) }
            ?: ruleCate
            ?: Config.defaultCategory(ctx).ifBlank { null }
            ?: "其它"
        // 二级分类要带上一级路径，钱迹才认
        val catename = if (type == 2 || type == 3) "" else store.categoryPath(rawCate)
        val desc = labelValue(nodes, profile.descLabels, requireCjk = true)
        val remark = buildRemark(merchant, desc)
        var coupon = if (profile.couponEnabled) {
            extractCoupon(scoped, profile.couponLabels)
        } else {
            ""
        }
        val originalPrice = if (profile.couponEnabled) {
            extractPrice(scoped, profile.priceLabels)
        } else {
            null
        }
        // 优惠行的名字五花八门（立减/抵价券/支付遇财神…），改看结构：值为负数的那一行就是优惠
        if (coupon.isBlank() && profile.couponEnabled) {
            coupon = negativeAmount(scoped, amountNode).orEmpty()
        }
        // 兜底：原价 - 实付
        if (coupon.isBlank() && profile.couponEnabled && originalPrice != null && amount > 0.0 &&
            originalPrice > amount + 0.001
        ) {
            coupon = String.format(java.util.Locale.US, "%.2f", originalPrice - amount)
        }
        val method = paymentMethod(scoped, profile.methodLabels)
        val account2Raw = if (profile.account2Labels.isEmpty()) {
            null
        } else {
            labelValue(scoped, profile.account2Labels)
        }
        val account2 = account2Raw?.let { raw ->
            AssetResolver.resolve(raw, assets, "").ifBlank { raw.trim() }
        }.orEmpty()
        val preferred = profile.preferredAssets.firstOrNull { name ->
            assets.any { it.equals(name, ignoreCase = true) }
        }
        // 付款方式能推断出资产就用它；推断不出来（如零钱、无该字段）才用场景偏好
        val byMethod = AssetResolver.resolve(method, assets, "").takeIf { it.isNotBlank() }
        val account = store.matchAssetRule(method)
            ?: byMethod
            ?: preferred
            ?: fallbackAccount

        // 钱迹的模型：money 记原价、coupon 记优惠，实付 = money - coupon
        var finalAmount = amount
        var finalCoupon = coupon
        var logAmount: Double? = null
        if (originalPrice != null) {
            finalAmount = originalPrice
            if (coupon.isNotBlank()) {
                val discount = coupon.toDoubleOrNull() ?: 0.0
                logAmount = (originalPrice - discount).coerceAtLeast(0.0)
            }
        } else if (coupon.isNotBlank()) {
            val discount = coupon.toDoubleOrNull() ?: 0.0
            if (profile.amountIsNet) {
                // 京东这类页面：大金额是优惠后的实付，原价 = 实付 + 优惠
                finalAmount = amount + discount
                logAmount = amount
            } else {
                // 默认：大金额是原价，实付 = 原价 - 优惠
                logAmount = (amount - discount).coerceAtLeast(0.0)
            }
        }
        return Draft(
            pkg, finalAmount, merchant, catename, account, book, type, remark,
            time = timeText, paymentMethod = method, coupon = finalCoupon,
            logAmount = logAmount, account2 = account2
        )
    }

    /**
     * 找页面文本里命中的标签，返回「标签 + 剩余值」。
     * 取最长匹配，避免「收款方全称」被当成「收款方」+「全称」。
     */
    private fun matchLabel(text: String, labels: List<String>): Pair<String, String>? {
        val label = labels.filter { text.contains(it) }.maxByOrNull { it.length } ?: return null
        val after = text.substringAfter(label)
        // 标签后面必须是结尾、空白或冒号；否则说明这是更长的字段名（「收款方名片」不是「收款方」）
        val separated = after.isEmpty() ||
            after.first().isWhitespace() ||
            after.first() == ':' || after.first() == '：'
        if (!separated) return null
        val rest = after.trim().trim(':', '：', ' ').trim()
        if (rest.isNotEmpty() && LABEL_FRAGMENTS.any { rest == it }) return null
        return label to rest
    }

    /**
     * 详情卡片常常盖在别的页面之上（京东钱包、支付宝首页…），
     * 用卡片里的字段标签定位范围，把底层页面的干扰节点排除掉。
     */
    private fun scopeNodes(nodes: List<NodeMeta>, rule: PayRules.Rule): List<NodeMeta> {
        val anchors = (rule.timeLabels + rule.methodLabels).distinct()
        if (anchors.isEmpty()) return nodes
        val anchorNodes = nodes.filter { n -> anchors.any { n.text.contains(it) } }
        if (anchorNodes.isEmpty()) return nodes
        val top = anchorNodes.minOf { it.top }
        val bottom = anchorNodes.maxOf { it.bottom }
        // 金额和商户在字段上方，向上留出余量
        val from = (top - 700).coerceAtLeast(0)
        val scoped = nodes.filter { it.top in from..(bottom + 120) }
        return if (scoped.size >= 4) scoped else nodes
    }

    /** 字段名碎片，不能当值用（页面常把「收款方全称」拆成两个节点） */
    private val LABEL_FRAGMENTS = listOf(
        "全称", "名称", "商户", "收款", "付款", "账户", "时间", "金额", "方式",
        "分类", "单号", "状态", "说明", "备注", "详情", "对方", "标签", "服务", "优惠"
    )

    /** 标签取值：同行内联 → 同一行右侧 → 紧邻下一行，并过滤字段名碎片 */
    private fun labelValue(
        nodes: List<NodeMeta>,
        labels: List<String>,
        requireCjk: Boolean = false
    ): String? {
        for ((i, n) in nodes.withIndex()) {
            val (label, inline) = matchLabel(n.text, labels) ?: continue
            if (usableValue(inline, requireCjk)) return inline

            val right = sameRowRight(nodes, n)?.takeIf { node ->
                labels.none { label2 -> node.text.contains(label2) }
            }
            if (right != null && usableValue(right.text, requireCjk)) return right.text.trim()

            for (j in i + 1 until minOf(i + 3, nodes.size)) {
                val text = nodes[j].text.trim()
                if (labels.any { text.contains(it) }) break
                if (usableValue(text, requireCjk)) return text
            }
        }
        return null
    }

    private fun usableValue(text: String, requireCjk: Boolean = false): Boolean {
        val v = text.trim()
        if (v.length !in 2..60) return false
        // 图片的 contentDescription 之类没有中文，别当值用
        if (requireCjk && v.none { it.code in 0x4E00..0x9FFF }) return false
        if (LABEL_FRAGMENTS.any { it == v }) return false
        if (LABEL_FRAGMENTS.any { v == it + "：" || v == it + ":" }) return false
        if (v.all { it.isDigit() || it == '.' || it == ':' }) return false
        // UI 用语不是值
        val uiWords = listOf(
            "添加", "请选择", "更多", "开关", "全部账单", "账单详情", "账单管理", "账单服务",
            "管理自动扣款", "立即领取", "我的消费图鉴", "为您推荐", "收起", "展开", "查看",
            "申请电子回单", "对此订单有疑问", "交易详情", "标签", "计入收支"
        )
        if (uiWords.any { v == it || v.startsWith(it) }) return false
        return v.any { it.code in 0x4E00..0x9FFF || it.isLetter() }
    }

    /** 备注：[插件-自动] 商户 · 商品说明 */
    private fun buildRemark(merchant: String, desc: String?): String {
        val clean = desc?.trim().orEmpty()
        return when {
            clean.isEmpty() || clean == merchant -> "[插件-自动] $merchant"
            merchant == "未知商户" -> "[插件-自动] $clean"
            else -> "[插件-自动] $merchant · $clean"
        }
    }

    /** 页面上值为负数的那一行（不依赖标签名），例如「-0.08」「-¥1.00」 */
    private fun negativeAmount(nodes: List<NodeMeta>, amountNode: NodeMeta): String? {
        val values = nodes.filter { it !== amountNode }
            .mapNotNull { n ->
                val text = n.text.trim()
                if (!text.startsWith("-")) return@mapNotNull null
                firstMoney(text)?.takeIf { it > 0.0 }
            }
        return values.maxOrNull()?.let { String.format(java.util.Locale.US, "%.2f", it) }
    }

    /** 原价：从「原价」那一行取数字，例如「￥500.00」→ 500.0 */
    private fun extractPrice(nodes: List<NodeMeta>, labels: List<String>): Double? {
        if (labels.isEmpty()) return null
        for ((i, n) in nodes.withIndex()) {
            val label = labels.firstOrNull { n.text.contains(it) } ?: continue
            val candidates = ArrayList<String>()
            candidates.add(n.text.substringAfter(label).trim())
            val right = sameRowRight(nodes, n)?.let { listOf(it) } ?: emptyList()
            candidates.addAll(right.map { it.text })
            nodes.getOrNull(i + 1)?.let { candidates.add(it.text) }
            for (text in candidates) {
                firstMoney(text)?.let { return it }
            }
        }
        return null
    }

    /** 优惠金额：从「优惠 / 折扣」那一行里取数字，例如「立减金优惠￥1.00」→ 1.00 */
    private fun extractCoupon(nodes: List<NodeMeta>, labels: List<String>): String {
        if (labels.isEmpty()) return ""
        for ((i, n) in nodes.withIndex()) {
            val label = labels.firstOrNull { n.text.contains(it) } ?: continue
            val candidates = ArrayList<String>()
            val inline = n.text.substringAfter(label).trim()
            candidates.add(inline)
            val right = sameRowRight(nodes, n)?.let { listOf(it) } ?: emptyList()
            candidates.addAll(right.map { it.text })
            nodes.getOrNull(i + 1)?.let { candidates.add(it.text) }
            for (text in candidates) {
                val value = firstMoney(text)
                if (value != null && value > 0.0) return String.format(Locale.US, "%.2f", value)
            }
        }
        return ""
    }

    /** 同一行右侧最近的节点（标签和值分属两个节点时用） */
    private fun sameRowRight(nodes: List<NodeMeta>, label: NodeMeta): NodeMeta? =
        nodes.filter {
            it !== label &&
                // 垂直方向必须落在标签这一行内，避免抓到上下错位的其他区块
                it.top >= label.top - 10 && it.bottom <= label.bottom + 10 &&
                it.left > label.left + 10
        }.minByOrNull { it.left }

    /** 从一段文本里取第一个金额数字（支持「¥12.90」「12.90元」「-0.43」这几种写法） */
    private fun firstMoney(text: String): Double? {
        SYMBOL_AMOUNT.find(text)?.let { m -> return m.groupValues[1].replace(",", "").toDoubleOrNull() }
        YUAN_AMOUNT.find(text)?.let { m -> return m.groupValues[1].replace(",", "").toDoubleOrNull() }
        // 纯数字（可能带正负号），优惠经常写成「-0.43」
        PLAIN_AMOUNT.find(text.trim())?.let { m ->
            return m.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return null
    }

    /** 页面自带的账单分类，比如支付宝的「亲友代付」 */
    private fun payCategory(nodes: List<NodeMeta>, labels: List<String>): String? {
        for ((i, n) in nodes.withIndex()) {
            val (_, inline) = matchLabel(n.text, labels) ?: continue
            if (inline.isNotEmpty()) return inline
            for (j in i + 1 until minOf(i + 4, nodes.size)) {
                val text = nodes[j].text.trim()
                if (text.isNotEmpty() && labels.none { it == text } && text.length <= 12) return text
            }
        }
        return null
    }


    /** 从「付款方式 / 支付方式」那一行取出具体账户，比如「民生银行储蓄卡(5711)」 */
    private fun paymentMethod(nodes: List<NodeMeta>, labels: List<String>): String? {
        val all = labels + listOf("付款账户", "支付账户")
        for ((i, n) in nodes.withIndex()) {
            val (_, inline) = matchLabel(n.text, all) ?: continue
            // 标签和值在同一个节点里
            if (inline.length >= 2) return inline
            // 同一行右侧最近的节点
            val right = sameRowRight(nodes, n)
            if (right != null && looksLikeAccount(right.text)) return right.text.trim()
            // 退化：下一个节点
            val next = nodes.getOrNull(i + 1)
            if (next != null && looksLikeAccount(next.text)) return next.text.trim()
        }
        return null
    }

    private fun looksLikeAccount(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..40) return false
        if (listOf("付款方式", "支付方式", "扣款方式", "交易方式", "付款账户", "支付账户").any { t == it }) return false
        if (candidate(NodeMeta(t, 0, 0, 0, 0, "")) != null) return false
        return t.any { it.code in 0x4E00..0x9FFF || it.isLetter() }
    }

    private fun candidate(node: NodeMeta): Candidate? {
        if (node.height < MIN_AMOUNT_HEIGHT) return null
        val s = node.text.trim()
        // 评分以字号为主：真正的金额通常字最大，货币符号只是小加成，
        // 避免「原价 ¥500.00」这种小字带符号的干扰项胜出。
        if (s.length <= 16) {
            SYMBOL_AMOUNT.find(s)?.let { m ->
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
                if (v != null && v > 0.0) return Candidate(node, v, node.height * 2 + 40)
            }
        }
        if (s.length <= 12) {
            YUAN_AMOUNT.find(s)?.let { m ->
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
                if (v != null && v > 0.0) return Candidate(node, v, node.height * 2 + 20)
            }
        }
        PLAIN_AMOUNT.find(s)?.let { m ->
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v > 0.0) return Candidate(node, v, node.height * 2 + 60)
        }
        return null
    }

    private fun findTime(nodes: List<NodeMeta>, labels: List<String> = emptyList()): TimeHit? {
        // 优先用「创建时间 / 支付时间」这类标签定位
        for ((i, n) in nodes.withIndex()) {
            val label = labels.firstOrNull { n.text.contains(it) } ?: continue
            val inline = n.text.substringAfter(label).trim().trim(':', '：').trim()
            parseTime(inline)?.let { (millis, normalized) -> return TimeHit(millis, normalized, i) }
            for (j in i + 1 until minOf(i + 3, nodes.size)) {
                parseTime(nodes[j].text)?.let { (millis, normalized) ->
                    return TimeHit(millis, normalized, j)
                }
            }
        }
        nodes.forEachIndexed { index, n ->
            parseTime(n.text)?.let { (millis, normalized) -> return TimeHit(millis, normalized, index) }
        }
        return null
    }

    /** 解析时间文本 → 毫秒；同时把格式统一成 yyyy-MM-dd HH:mm:ss */
    private fun parseTime(text: String): Pair<Long, String>? {
        val iso = TIME_RE.find(text)
        val cn = TIME_CN.find(text)
        val groups = when {
            iso != null -> iso.groupValues
            cn != null -> cn.groupValues
            else -> return null
        }
        val y = groups[1].toInt()
        val mo = groups[2].toInt()
        val d = groups[3].toInt()
        val h = groups[4].toInt()
        val mi = groups[5].toInt()
        val s = groups[6].toInt()
        val normalized = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", y, mo, d, h, mi, s)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val millis = try {
            fmt.parse(normalized)?.time
        } catch (e: Exception) {
            null
        } ?: return null
        return millis to normalized
    }

    private fun hasTime(text: String): Boolean =
        TIME_RE.containsMatchIn(text) || TIME_CN.containsMatchIn(text)

    private fun merchantFromDetail(
        nodes: List<NodeMeta>,
        amountNode: NodeMeta,
        fieldLabels: List<String>
    ): String {
        val timeIdx = nodes.indices.filter { hasTime(nodes[it].text) }
        for (idx in timeIdx) {
            var steps = 0
            var i = idx - 1
            while (i >= 0 && steps < 10) {
                if (fieldLabels.none { nodes[i].text.contains(it) } && validMerchantText(nodes[i])) {
                    return cleanMerchant(nodes[i].text.trim())
                }
                i--
                steps++
            }
        }
        return "未知商户"
    }

    /** 判断收支方向：先用当前详情页规则里的提示词，再看通用标签 */
    private fun detectType(nodes: List<NodeMeta>, all: String, rule: PayRules.Rule): Int {
        val texts = nodes.map { it.text.trim() }
        fun exact(vararg labels: String) = texts.any { t -> labels.any { it == t } }

        // 页面上直接写着「收入 / 支出」标签
        if (exact("收入", "收入金额", "收款金额")) return 1
        if (exact("支出", "支出金额", "付款金额")) return 0

        // 规则自带的收支特征词
        if (rule.incomeMarks.any { all.contains(it) }) return 1
        if (rule.expenseMarks.any { all.contains(it) }) return 0

        // 退款、收款成功属于收入
        if (all.contains("退款成功") || all.contains("已退款") ||
            all.contains("收款成功") || all.contains("到账成功")
        ) return 1

        // 页面有「付款方式 / 扣款方式」说明是我在付钱（收入页一般是「收款方式 / 到账账户」）
        val iPaid = all.contains("付款方式") || all.contains("扣款方式") || all.contains("支付方式")
        val iReceived = all.contains("收款方式") || all.contains("到账账户") || all.contains("入账账户")
        if (iPaid && !iReceived) return 0

        // 「付款方」= 别人付给我；「收款方」= 我付给别人
        val paidToMe = all.contains("付款方")
        val paidByMe = all.contains("收款方")
        if (paidToMe && !paidByMe) return 1

        // 其余按支出处理（自动记账绝大多数是消费）
        return 0
    }

    /** 转账页标题里常混着「转账-转给」「xxx元」，清理成对方名称 */
    private fun cleanMerchant(raw: String): String {
        var text = raw.trim()
        listOf(
            "还款:", "还款：", "还款-",
            "收到转账：来自", "收到转账:来自",
            "转账：转给", "转账:转给", "转账：收到来自", "转账:收到来自",
            "转账:", "转账：",
            "转账-来自", "转账-发给", "转账-",
            "微信红包-来自", "微信红包-发给", "微信红包-", "红包-来自", "红包-发给",
            "扫二维码付款-给", "扫二维码付款-", "扫码付款-给", "扫码付款-",
            "转账-转给", "转账-收到来自", "转账给", "转给", "收到来自"
        ).forEach { prefix ->
            if (text.contains(prefix)) text = text.substringAfter(prefix)
        }
        text = text.replace(Regex("""\s*[0-9][0-9,.]*元"""), "").trim()
        text = text.trim('-', '—', ' ', ':', '：')
        if (text.isEmpty()) text = raw.trim()
        return if (text.length > 30) text.take(30) else text
    }

    /** 没有商户字段的页面：按「旗舰店 / 专营店」这类后缀找店铺名 */
    private fun merchantBySuffix(nodes: List<NodeMeta>, suffixes: List<String>): String? {
        if (suffixes.isEmpty()) return null
        return nodes.filter { n ->
            val text = n.text.trim()
            text.length in 2..40 && suffixes.any { text.endsWith(it) } && validMerchantText(n)
        }.minByOrNull { it.top }?.text?.trim()
    }

    /** 金额正上方的那一行文本（亲密付、代付这类页面商户名就在金额上面） */
    private fun merchantAboveAmount(
        nodes: List<NodeMeta>,
        amountNode: NodeMeta,
        fieldLabels: List<String>
    ): String? {
        // 转账页的对方名称在标题里，可能比较长，这里放宽长度限制
        val above = nodes.filter { n ->
            val text = n.text.trim()
            n !== amountNode &&
                n.bottom <= amountNode.top + 8 &&
                amountNode.top - n.bottom < 260 &&
                kotlin.math.abs((n.left + n.right) / 2 - (amountNode.left + amountNode.right) / 2) < 240 &&
                fieldLabels.none { text.contains(it) } &&
                text.length in 2..60 &&
                text.any { it.code in 0x4E00..0x9FFF } &&
                // 纯金额行（如「¥580.00」）排除，但标题里带「580元」的长文本要保留
                (text.length > 12 || candidate(n) == null)
        }
        // 取离金额最近的那一行
        return above.maxByOrNull { it.bottom }?.text?.trim()?.let { cleanMerchant(it) }
    }

    /** 优先按「收款方 / 商户全称」这类标签取值，取不到再退回位置启发式 */
    private fun merchantFromLabel(nodes: List<NodeMeta>, labels: List<String>): String? {
        for ((i, n) in nodes.withIndex()) {
            val (label, inline) = matchLabel(n.text, labels) ?: continue
            if (inline.isNotEmpty() && validMerchantText(NodeMeta(inline, 0, 0, 0, 0, ""))) {
                return inline
            }
            // 同一行右侧的值（「收款方  某某商户」是两个节点的情况）
            val right = sameRowRight(nodes, n)?.takeIf { node ->
                !labels.any { label -> node.text.contains(label) }
            }
            if (right != null && validMerchantText(right)) return right.text.trim()
            // 换行后的下一行
            for (j in i + 1 until minOf(i + 3, nodes.size)) {
                if (labels.none { nodes[j].text.contains(it) } && validMerchantText(nodes[j])) {
                    return cleanMerchant(nodes[j].text.trim())
                }
            }
        }
        return null
    }

    private fun validMerchantText(node: NodeMeta): Boolean {
        val t = node.text.trim()
        // 单字多半是字段名碎片（如「式」），不能当商户
        if (t.length !in 2..30) return false
        if (candidate(node) != null) return false
        if (hasTime(t)) return false
        if (DETAIL_LABELS.any { t.contains(it) }) return false
        if (BLOCK_WORDS.any { t.contains(it) }) return false
        // 纯字段名 / 字段名碎片不能当商户（如「全称」「收款方」单独一行）
        if (LABEL_FRAGMENTS.any { t == it || t == it + "：" || t == it + ":" }) return false
        if (listOf("收款方", "付款方", "商户名称", "商户全称", "对方").any { t == it || t == it + "：" || t == it + ":" }) return false
        if (t.none { it.code in 0x4E00..0x9FFF || it.isLetter() }) return false
        return true
    }

    /** 有金额又有时间，但没匹配到任何规则——大概率是还没适配的详情页 */
    fun looksLikeDetail(nodes: List<NodeMeta>): Boolean {
        if (findTime(nodes) == null) return false
        return nodes.any { candidate(it) != null }
    }

    fun isBillPage(ctx: Context, pkg: String, nodes: List<NodeMeta>): Boolean {
        val all = nodes.joinToString(" ") { it.text }
        return PayRules.pick(ctx, pkg, all) != null
    }

    fun debugSummary(ctx: Context, pkg: String, nodes: List<NodeMeta>): String {
        val all = nodes.joinToString(" ") { it.text }
        val rule = PayRules.pick(ctx, pkg, all)
        val hit = findTime(nodes)
        val amounts = nodes.mapNotNull { candidate(it) }
            .sortedByDescending { it.score }
            .take(4)
            .joinToString(",") { "${it.value}@${it.node.height}px" }
        return "app=${PayRules.appOf(ctx, pkg).name} rule=${rule?.id ?: "无"} time=${hit?.text} amounts=[$amounts]"
    }

    fun debugTexts(nodes: List<NodeMeta>, limit: Int = 40): String =
        nodes.take(limit).joinToString(" | ") { it.text.replace('\n', ' ') }
}
