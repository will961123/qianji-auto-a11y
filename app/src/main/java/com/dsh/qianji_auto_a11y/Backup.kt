package com.dsh.qianji_auto_a11y

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本应用数据的导出 / 导入（JSON）。
 *
 * 和「从钱迹导入」不同：那边读钱迹的 Excel，这边是本应用自己的完整备份，
 * 可以按需勾选要处理的几块数据。
 */
object Backup {

    const val APP_ID = "autoqianji"
    const val VERSION = 1

    /** 各数据块的勾选状态；日志默认不导出 */
    data class Selection(
        val categoryRules: Boolean = true,
        val assetRules: Boolean = true,
        val defaults: Boolean = true,
        val assets: Boolean = true,
        val categories: Boolean = true,
        val books: Boolean = true,
        val logs: Boolean = false
    ) {
        fun isEmpty(): Boolean =
            !categoryRules && !assetRules && !defaults && !assets && !categories && !books && !logs

        companion object {
            val LABELS = listOf(
                "分类匹配规则",
                "资产匹配规则",
                "默认值",
                "资产列表",
                "分类列表",
                "账本列表",
                "记账记录"
            )

            fun of(checked: BooleanArray) = Selection(
                categoryRules = checked[0],
                assetRules = checked[1],
                defaults = checked[2],
                assets = checked[3],
                categories = checked[4],
                books = checked[5],
                logs = checked[6]
            )

            fun defaultChecked(): BooleanArray =
                booleanArrayOf(true, true, true, true, true, true, false)
        }
    }

    // ---------------- 导出 ----------------

    fun export(ctx: Context, store: Store, sel: Selection): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        if (sel.categoryRules || sel.assetRules) {
            val rules = JSONObject()
            if (sel.categoryRules) rules.put("category", rulesArray(store.rulesOf(Store.RULE_CATEGORY)))
            if (sel.assetRules) rules.put("asset", rulesArray(store.rulesOf(Store.RULE_ASSET)))
            root.put("rules", rules)
        }

        if (sel.defaults) {
            root.put(
                "defaults",
                JSONObject().apply {
                    put("account", Config.defaultAccount(ctx))
                    put("category", Config.defaultCategory(ctx))
                    put("book", Config.book(ctx))
                }
            )
        }

        if (sel.assets) {
            root.put(
                "assets",
                JSONArray().apply {
                    store.assetsWithGroup().forEach { (name, group) ->
                        put(JSONObject().apply { put("name", name); put("group", group) })
                    }
                }
            )
        }

        if (sel.categories) {
            root.put(
                "categories",
                JSONArray().apply {
                    store.categoryTree().forEach { item ->
                        put(JSONObject().apply {
                            put("name", item.name)
                            put("parent", item.parent)
                        })
                    }
                }
            )
        }

        if (sel.books) {
            root.put("books", JSONArray().apply { store.books().forEach { put(it) } })
        }

        if (sel.logs) {
            root.put(
                "logs",
                JSONArray().apply {
                    store.allLogs().forEach { r ->
                        put(JSONObject().apply {
                            put("ts", r.ts)
                            put("pkg", r.pkg)
                            put("amount", r.amount)
                            put("merchant", r.merchant)
                            put("catename", r.catename)
                            put("status", r.status)
                            put("detail", r.detail)
                            put("account", r.account)
                            put("type", r.type)
                            put("billtime", r.billtime)
                            put("coupon", r.coupon)
                            put("account2", r.account2)
                        })
                    }
                }
            )
        }
        return root.toString(2)
    }

    private fun rulesArray(list: List<Pair<String, String>>) = JSONArray().apply {
        list.forEach { (keyword, target) ->
            put(JSONObject().apply {
                put("keyword", keyword)
                put("target", target)
            })
        }
    }

    // ---------------- 导入 ----------------

    /** 返回给用户看的结果摘要；格式不对抛异常 */
    fun import(ctx: Context, store: Store, text: String, sel: Selection): String {
        val root = JSONObject(text)
        val app = root.optString("app", "")
        if (app != APP_ID) throw IllegalArgumentException("不是本应用导出的备份文件")

        val done = ArrayList<String>()

        root.optJSONObject("rules")?.let { rules ->
            if (sel.categoryRules) {
                val list = parseRules(rules.optJSONArray("category"))
                store.replaceRules(Store.RULE_CATEGORY, list)
                done.add("分类规则 ${list.size}")
            }
            if (sel.assetRules) {
                val list = parseRules(rules.optJSONArray("asset"))
                store.replaceRules(Store.RULE_ASSET, list)
                done.add("资产规则 ${list.size}")
            }
        }

        if (sel.defaults) {
            root.optJSONObject("defaults")?.let { d ->
                Config.setDefaultAccount(ctx, d.optString("account", ""))
                Config.setDefaultCategory(ctx, d.optString("category", ""))
                Config.setBook(ctx, d.optString("book", ""))
                done.add("默认值")
            }
        }

        if (sel.assets) {
            root.optJSONArray("assets")?.let { arr ->
                val list = ArrayList<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    if (name.isNotEmpty()) list.add(name to o.optString("group", ""))
                }
                store.replaceAssets(list)
                done.add("资产 ${list.size}")
            }
        }

        if (sel.categories) {
            root.optJSONArray("categories")?.let { arr ->
                val list = ArrayList<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    if (name.isNotEmpty()) list.add(name to o.optString("parent", ""))
                }
                // 内置分类始终保留
                Builtin.protectedCategories.forEach { name ->
                    if (list.none { it.first == name && it.second.isBlank() }) list.add(name to "")
                }
                store.replaceCategories(list)
                done.add("分类 ${list.size}")
            }
        }

        if (sel.books) {
            root.optJSONArray("books")?.let { arr ->
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i, "").trim()
                    if (name.isNotEmpty()) list.add(name)
                }
                store.replaceBooks(list)
                done.add("账本 ${list.size}")
            }
        }

        if (sel.logs) {
            root.optJSONArray("logs")?.let { arr ->
                val rows = ArrayList<Store.LogRow>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val row = Store.LogRow(
                        ts = o.optLong("ts", 0L),
                        pkg = o.optString("pkg", ""),
                        amount = o.optString("amount", ""),
                        merchant = o.optString("merchant", ""),
                        catename = o.optString("catename", ""),
                        status = o.optString("status", ""),
                        detail = o.optString("detail", ""),
                        account = o.optString("account", ""),
                        type = o.optInt("type", 0),
                        billtime = o.optString("billtime", ""),
                        coupon = o.optString("coupon", ""),
                        account2 = o.optString("account2", "")
                    )
                    if (row.ts > 0) rows.add(row)
                }
                // 完整覆盖：先清空再写入
                store.replaceLogs(rows)
                done.add("记账记录 ${rows.size}")
            }
        }

        if (done.isEmpty()) throw IllegalArgumentException("没有勾选可导入的内容")
        return done.joinToString("、")
    }

    private fun parseRules(arr: JSONArray?): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        if (arr == null) return list
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val keyword = o.optString("keyword", "").trim()
            val target = o.optString("target", "").trim()
            if (keyword.isNotEmpty()) list.add(keyword to target)
        }
        return list
    }
}
