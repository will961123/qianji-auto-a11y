package com.dsh.qianji_auto_a11y

import android.content.Context
import org.json.JSONObject

/**
 * 账单详情页规则表，数据驱动：
 * 内置规则放在 assets/pay_rules.json，新增 App / 新场景只要加一条 JSON，不用改代码。
 *
 * JSON 结构：
 * {
 *   "apps": [
 *     {
 *       "name": "支付宝",
 *       "packages": ["com.eg.android.AlipayGphone"],
 *       "rules": [
 *         {
 *           "id": "支付宝·消费",
 *           "marks": ["账单详情", "收款方"],        // 页面特征词，命中越多越优先
 *           "merchant": ["收款方全称", "收款方"],     // 商户名标签
 *           "desc": ["商品说明"],                  // 说明文字标签
 *           "time": ["创建时间", "支付时间"],
 *           "method": ["付款方式"],
 *           "category": ["账单分类"],
 *           "income": ["付款方"],                  // 命中判为收入
 *           "expense": ["收款方"]                  // 命中判为支出
 *         }
 *       ]
 *     }
 *   ]
 * }
 */
object PayRules {

    data class Rule(
        val id: String,
        val marks: List<String> = emptyList(),
        val merchantLabels: List<String> = emptyList(),
        val descLabels: List<String> = emptyList(),
        val timeLabels: List<String> = emptyList(),
        val methodLabels: List<String> = emptyList(),
        val categoryLabels: List<String> = emptyList(),
        val couponLabels: List<String> = emptyList(),
        val priceLabels: List<String> = emptyList(),
        /** 这个场景优先使用的资产（按顺序找第一个存在的） */
        val preferredAssets: List<String> = emptyList(),
        /** 这个场景的默认分类（形如「一级/::/二级」），关键词规则没命中时才用 */
        val defaultCategory: String = "",
        /** 页面上的大金额是「优惠后」的实付（京东等），原价需要加上优惠 */
        val amountIsNet: Boolean = false,
        /** 直接指定金额字段的标签（如「实付款」），优先于字号评分 */
        val amountLabels: List<String> = emptyList(),
        /** 商户名后缀，如「旗舰店」「专营店」，用于没有商户字段的页面 */
        val merchantSuffixes: List<String> = emptyList(),
        /** 页面没有账单时间时允许用当前时间 */
        val timeOptional: Boolean = false,
        /** 是否识别优惠（抖音这类大额券场景可以关掉） */
        val couponEnabled: Boolean = true,
        /** 固定账单类型：0 支出 / 1 收入 / 2 转账 / 3 还款 / 5 报销，-1 表示按页面判断 */
        val fixedType: Int = -1,
        /** 转入账户（accountname2）的标签，如「信用卡号」 */
        val account2Labels: List<String> = emptyList(),
        val incomeMarks: List<String> = emptyList(),
        val expenseMarks: List<String> = emptyList()
    )

    data class App(val name: String, val packages: List<String>, val rules: List<Rule>)

    @Volatile
    private var apps: List<App>? = null

    /** 兜底规则：没匹配到具体 App 时用，任何「详情页」都能跑 */
    private val GENERIC = App(
        name = "通用",
        packages = emptyList(),
        rules = listOf(
            Rule(
                id = "通用·详情",
                marks = listOf("账单详情", "交易详情", "交易记录详情", "交易明细"),
                merchantLabels = listOf("商户名称", "商户全称", "收款方全称", "收款方", "对方", "交易对方"),
                descLabels = listOf("商品说明", "交易说明", "摘要"),
                timeLabels = listOf("创建时间", "交易时间", "支付时间"),
                methodLabels = listOf("付款方式", "支付方式", "交易方式", "扣款方式"),
                categoryLabels = listOf("账单分类", "交易分类"),
                incomeMarks = listOf("收款成功", "到账成功", "退款成功", "入账", "收入")
            )
        )
    )

    fun load(ctx: Context) {
        if (apps != null) return
        synchronized(this) {
            if (apps != null) return
            apps = runCatching { parse(ctx) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: emptyList()
        }
    }

    fun appOf(ctx: Context, pkg: String): App {
        load(ctx)
        val list = apps.orEmpty()
        return list.firstOrNull { app -> app.packages.any { pkg.equals(it, ignoreCase = true) } }
            ?: GENERIC
    }

    /**
     * 按页面文字挑规则：本 App 的规则里命中特征词最多的胜出；
     * 都不命中就回落到通用规则；再不行返回 null（说明不是详情页）。
     */
    fun pick(ctx: Context, pkg: String, all: String): Rule? {
        val app = appOf(ctx, pkg)
        // 已预置但还没配规则的 App：不猜测，直接跳过（等真机测好再补 JSON）
        if (app !== GENERIC && app.rules.isEmpty()) return null
        val own = app.rules
            .map { rule -> rule to rule.marks.count { all.contains(it) } }
            .maxByOrNull { it.second }
        if (own != null && own.second > 0) return own.first

        if (app !== GENERIC) {
            val generic = GENERIC.rules.first()
            if (generic.marks.any { all.contains(it) }) return generic
        }
        return null
    }

    /** 规则表里覆盖到的全部包名（用于无障碍事件过滤） */
    fun supportedPackages(ctx: Context): Set<String> {
        load(ctx)
        return apps.orEmpty().flatMap { it.packages }.toSet()
    }

    /** 所有已内置的 App，供设置页展示 */
    fun appNames(): List<String> {
        val list = apps.orEmpty().map { it.name }
        return if (list.isEmpty()) listOf(GENERIC.name) else list + GENERIC.name
    }

    private fun parse(ctx: Context): List<App> {
        val text = ctx.assets.open("pay_rules.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val appsArray = root.optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<App>()
        for (i in 0 until appsArray.length()) {
            val appObj = appsArray.optJSONObject(i) ?: continue
            val name = appObj.optString("name").ifBlank { "未命名" }
            val packages = appObj.optJSONArray("packages")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val rulesArray = appObj.optJSONArray("rules") ?: continue
            val rules = ArrayList<Rule>()
            for (j in 0 until rulesArray.length()) {
                val r = rulesArray.optJSONObject(j) ?: continue
                val id = r.optString("id").ifBlank { "$name·规则${j + 1}" }
                rules.add(
                    Rule(
                        id = id,
                        marks = r.strings("marks"),
                        merchantLabels = r.strings("merchant"),
                        descLabels = r.strings("desc"),
                        timeLabels = r.strings("time"),
                        methodLabels = r.strings("method"),
                        categoryLabels = r.strings("category"),
                        couponLabels = r.strings("coupon"),
                        priceLabels = r.strings("price"),
                        preferredAssets = r.strings("asset"),
                        defaultCategory = r.optString("defaultCate").orEmpty(),
                        amountIsNet = r.optBoolean("amountIsNet", false),
                        amountLabels = r.strings("amount"),
                        merchantSuffixes = r.strings("merchantSuffix"),
                        timeOptional = r.optBoolean("timeOptional", false),
                        couponEnabled = r.optBoolean("couponEnabled", true),
                        fixedType = if (r.has("type")) r.optInt("type", -1) else -1,
                        account2Labels = r.strings("account2"),
                        incomeMarks = r.strings("income"),
                        expenseMarks = r.strings("expense")
                    )
                )
            }
            if (rules.isNotEmpty()) out.add(App(name, packages, rules))
        }
        return out
    }

    private fun JSONObject.strings(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf(String::isNotBlank) }
    }
}
