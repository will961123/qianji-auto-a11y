package com.dsh.autoqianji

/**
 * 从页面的「付款方式」推断钱迹里对应的资产。
 * 例如：
 *   民生银行储蓄卡(5711)  → 民生银行
 *   中信银行信用卡(1278)  → 中信银行信用卡
 *   花呗 / 余额宝 / 零钱  → 命中同名资产，否则回落到配置的默认资产
 */
object AssetResolver {

    private val PAREN = Regex("[（(][^)）]*[)）]")
    private val DIGITS = Regex("\\d{4,}")

    fun resolve(raw: String?, assets: List<String>, fallback: String): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || assets.isEmpty()) return fallback

        val norm = normalize(text)
        if (norm.isEmpty()) return fallback

        val core = stripCardSuffix(norm)
        val rawCredit = text.contains("信用卡")
        val rawDebit = text.contains("储蓄卡") || text.contains("借记卡")

        var best: String? = null
        var bestScore = 0

        for (a in assets) {
            val aNorm = normalize(a)
            val aCore = stripCardSuffix(aNorm)
            val aCredit = aNorm.contains("信用卡")

            var score = when {
                aNorm == norm -> 100
                // 资产名完整出现在付款方式里：名字越长越具体，优先选它
                norm.contains(aNorm) -> 90 + aNorm.length
                aCore.isNotEmpty() && aCore == core -> 85
                aCore.isNotEmpty() && core.contains(aCore) -> 70 + aCore.length
                core.isNotEmpty() && aCore.contains(core) -> 60 + core.length
                else -> 0
            }
            if (score == 0) continue

            // 卡种要一致：储蓄卡别匹配到信用卡资产上
            if (rawCredit && !aCredit) score -= 40
            if (rawDebit && aCredit) score -= 60

            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return if (bestScore >= 50 && best != null) best else fallback
    }

    private fun normalize(s: String): String =
        s.replace(PAREN, "").replace(DIGITS, "").trim()

    private fun stripCardSuffix(s: String): String =
        s.replace("储蓄卡", "").replace("信用卡", "").replace("借记卡", "")
            .replace("银行卡", "").trim()
}
