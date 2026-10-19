package com.dsh.qianji_auto_a11y

/**
 * 出厂内置规则：只用来「播种」，播种后就是普通数据，
 * 可以编辑、删除、排序，删坏了可以用「恢复内置规则」找回来。
 *
 * 目标写的是钱迹里真实存在的分类名 / 二级路径，不做别名猜测。
 * 这份内容以当前设备上调整后的规则为准。
 */
object Builtin {

    /** 内置分类：始终存在，不允许删除；它们下面的二级分类可以正常增删 */
    val protectedCategories = listOf("其它")

    data class Rule(val keyword: String, val target: String)

    /** 分类：目标是「一级」或「一级/::/二级」 */
    val categoryRules = listOf(
        Rule("滴滴", "交通/::/打车"),
        Rule("高德打车", "交通/::/打车"),
        Rule("12306", "交通/::/火车"),
        Rule("中国石化", "汽车/加油/::/油费"),
        Rule("中国石油", "汽车/加油/::/油费"),
        Rule("美团", "零食/::/外卖"),
        Rule("饿了么", "零食/::/外卖"),
        Rule("瑞幸", "零食/::/奶茶"),
        Rule("京东", "电器数码"),
        Rule("淘宝", "衣服"),
        Rule("拼多多", "日用品"),
        Rule("中国移动", "话费网费/::/联通"),
        Rule("中国联通", "话费网费/::/联通"),
        Rule("中国电信", "话费网费"),
        Rule("房租", "住房/::/房租"),
        Rule("房贷", "住房/::/房贷"),
        Rule("电费", "住房/::/水电煤"),
        Rule("水费", "住房/::/水电煤"),
        // 亲情卡（亲密付）走的是代付，商户名往往是人名，靠页面文字识别
        Rule("亲情卡消费", "亲密付"),
        Rule("亲友代付", "亲密付")
    )

    /** 资产：付款方式里出现这些词，就用对应资产 */
    val assetRules = listOf(
        Rule("微信", "微信"),
        Rule("支付宝", "支付宝")
    )

    /**
     * 支付页面自带的账单分类 → 用户分类的候选名。
     * 支付宝/微信的分类叫法和钱迹不一样，这里做一层映射，命中哪个用哪个。
     */
    private val pageCategoryAliases = mapOf(
        "亲友代付" to listOf("亲密付", "亲情卡", "亲友代付", "人情"),
        "餐饮美食" to listOf("三餐", "餐饮", "吃饭", "伙食", "零食"),
        "交通出行" to listOf("交通", "出行", "打车", "公共交通"),
        "日用百货" to listOf("日用品", "生活日用", "日用", "购物"),
        "服饰装扮" to listOf("衣服", "服饰", "美妆", "穿搭"),
        "数码电器" to listOf("电器数码", "数码", "电器", "电子产品"),
        "医疗健康" to listOf("医疗", "健康", "药品", "看病"),
        "住房物业" to listOf("住房", "房租", "物业", "居住"),
        "充值缴费" to listOf("话费网费", "话费", "通讯", "充值", "缴费"),
        "转账红包" to listOf("发红包", "红包", "转账", "人情"),
        "文化休闲" to listOf("娱乐", "休闲", "学习", "影音"),
        "教育培训" to listOf("学习", "教育", "培训"),
        "酒店旅游" to listOf("旅行", "出行", "交通", "住宿"),
        "运动户外" to listOf("运动", "骑行", "健身", "娱乐"),
        "母婴亲子" to listOf("孩子", "母婴", "亲子"),
        "爱车养车" to listOf("汽车/加油", "养车", "加油", "交通"),
        "保险" to listOf("保险", "车险", "医保", "其它"),
        "转账" to listOf("转账", "其它"),
        "其他" to listOf("其它", "其他")
    )

    /** 把页面分类映射到用户已有分类；映射不到就返回 null */
    fun resolvePageCategory(pageName: String, available: List<String>): String? {
        if (pageName.isBlank() || available.isEmpty()) return null
        if (available.contains(pageName)) return pageName
        val candidates = pageCategoryAliases[pageName] ?: return null
        for (name in candidates) {
            if (available.contains(name)) return name
        }
        return null
    }

    /**
     * 目标必须真实存在才生效：
     * 一级名要能在分类库里找到；二级路径要求一级和二级都在。
     */
    fun resolve(target: String, available: List<String>): String? {
        if (available.isEmpty()) return null
        if (target.contains("/::/")) {
            val parts = target.split("/::/")
            return if (parts.all { available.contains(it) }) target else null
        }
        return if (available.contains(target)) target else null
    }
}
