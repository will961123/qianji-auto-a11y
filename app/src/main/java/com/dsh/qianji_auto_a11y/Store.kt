package com.dsh.qianji_auto_a11y

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CategoryItem(val name: String, val parent: String, val isChild: Boolean)

class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "auto.db", null, DB_VERSION) {

    companion object {
        const val KIND_ASSET = "asset"
        /** 还没发布，表结构只要变就直接重建，不做数据迁移 */
        const val DB_VERSION = 1

        const val KIND_CATEGORY = "category"
        const val KIND_BOOK = "book"

        const val GRP_CREDIT = "credit"
        const val GRP_ASSET = "asset"

        const val RULE_CATEGORY = "category"
        const val RULE_ASSET = "asset"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAll(db)
        seedBuiltins(db)
    }

    private fun createAll(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rules(_id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT NOT NULL, catename TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'category', sort INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS logs(_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, pkg TEXT, amount TEXT, merchant TEXT, catename TEXT, status TEXT, detail TEXT, account TEXT, type INTEGER NOT NULL DEFAULT 0, billtime TEXT NOT NULL DEFAULT '', coupon TEXT NOT NULL DEFAULT '', account2 TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS assets(name TEXT PRIMARY KEY, sort INTEGER NOT NULL DEFAULT 0, grp TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS categories(name TEXT NOT NULL, parent TEXT NOT NULL DEFAULT '', sort INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(name, parent))")
        db.execSQL("CREATE TABLE IF NOT EXISTS books(name TEXT PRIMARY KEY, sort INTEGER NOT NULL DEFAULT 0)")
    }

    /** 建库时只补内置分类；匹配规则全部由用户自己添加 */
    private fun seedBuiltins(db: SQLiteDatabase) {
        // 内置分类始终存在（用户删除后重建时也会补回来）
        Builtin.protectedCategories.forEach { name ->
            db.execSQL("INSERT OR IGNORE INTO categories(name, parent, sort) VALUES(?, '', 0)", arrayOf(name))
        }
    }



    /**
     * 增量迁移表：key 是「升到哪个版本」时要做的事。
     *
     * 现在还没发布，表结构还在动，所以留空 —— 留空时整库重建（等同新装）。
     * 以后要保住用户数据，就：
     *   1. DB_VERSION 加 1
     *   2. 在这里补一条 `2 to { db -> db.execSQL("ALTER TABLE ...") }`
     * 已有版本的用户走增量，不丢数据。
     */
    private val migrations: Map<Int, (SQLiteDatabase) -> Unit> = emptyMap()

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (migrations.isEmpty()) {
            // 开发期：没有迁移脚本，直接重建
            recreateAll(db)
            return
        }
        var version = oldVersion
        while (version < newVersion) {
            version++
            migrations[version]?.invoke(db)
        }
        // 迁移脚本只改已有表，新表/新列在这里补齐
        createAll(db)
        seedBuiltins(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        recreateAll(db)
    }


    private fun recreateAll(db: SQLiteDatabase) {
        for (t in listOf("rules", "logs", "assets", "categories", "books")) {
            db.execSQL("DROP TABLE IF EXISTS $t")
        }
        createAll(db)
        seedBuiltins(db)
    }

    /** categories 里 parent 为空表示一级分类；插入顺序即默认排序 */
    fun replaceLookups(assets: List<String>, categories: List<Pair<String, String?>>, books: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assets", null, null)
            db.delete("categories", null, null)
            db.delete("books", null, null)
            assets.forEachIndexed { i, a ->
                db.insertWithOnConflict("assets", null, ContentValues().apply {
                    put("name", a); put("sort", i); put("grp", guessGroup(a))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            categories.forEachIndexed { i, (name, parent) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("name", name); put("parent", parent ?: ""); put("sort", i)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            books.forEachIndexed { i, b ->
                db.insertWithOnConflict("books", null, ContentValues().apply {
                    put("name", b); put("sort", i)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            // 内置分类：导入文件里没有也要补上
            Builtin.protectedCategories.forEach { name ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("name", name); put("parent", ""); put("sort", 9999)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun assets(): List<String> = namesOf("assets")
    fun books(): List<String> = namesOf("books")

    /** 只取分类名，给规则下拉用 */
    fun categories(): List<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT name FROM categories ORDER BY sort, name", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out.toList()
    }

    /** 分类名补成路径：二级分类返回「一级/::/二级」，一级分类原样返回 */
    fun categoryPath(name: String): String {
        if (name.isBlank() || name.contains("/::/")) return name
        readableDatabase.rawQuery("SELECT parent FROM categories WHERE name=?", arrayOf(name)).use { c ->
            if (c.moveToNext()) {
                val parent = c.getString(0).orEmpty()
                if (parent.isNotBlank() && parent != name) return "$parent/::/$name"
            }
        }
        return name
    }

    /** 树形顺序：每个一级后面紧跟它的二级；顺序按 sort 字段（可拖拽调整） */
    fun categoryTree(): List<CategoryItem> {
        val children = HashMap<String, MutableList<String>>()
        val roots = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name, parent FROM categories ORDER BY sort, name", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val parent = c.getString(1) ?: ""
                if (parent.isEmpty()) {
                    if (!roots.contains(name)) roots.add(name)
                } else {
                    children.getOrPut(parent) { ArrayList() }.let { if (!it.contains(name)) it.add(name) }
                }
            }
        }
        val out = ArrayList<CategoryItem>()
        for (root in roots) {
            out.add(CategoryItem(root, "", false))
            children[root]?.forEach { out.add(CategoryItem(it, root, true)) }
        }
        val known = roots.toSet()
        for ((parent, kids) in children) {
            if (parent in known) continue
            for (k in kids) out.add(CategoryItem(k, parent, true))
        }
        return out
    }

    fun countOf(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun addLookup(kind: String, name: String, group: String = "") {
        val table = tableOf(kind)
        val next = countOf(table)
        val v = ContentValues().apply {
            put("name", name)
            put("sort", next)
            when (kind) {
                KIND_CATEGORY -> put("parent", group)
                KIND_ASSET -> put("grp", group.ifBlank { guessGroup(name) })
            }
        }
        writableDatabase.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun guessGroup(name: String): String =
        if (name.contains("信用卡")) GRP_CREDIT else GRP_ASSET

    /** (名称, 分组) 按顺序，分组为空时按名字推断 */
    fun assetsWithGroup(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT name, grp FROM assets ORDER BY sort, name", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val grp = c.getString(1)?.ifBlank { guessGroup(name) } ?: guessGroup(name)
                out.add(name to grp)
            }
        }
        return out
    }

    fun deleteLookup(kind: String, name: String) {
        val db = writableDatabase
        if (kind == KIND_CATEGORY) {
            // 一级分类被删时，它下面的二级分类一起清掉，避免留下孤儿
            db.beginTransaction()
            try {
                db.delete("categories", "parent=?", arrayOf(name))
                db.delete("categories", "name=?", arrayOf(name))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } else {
            db.delete(tableOf(kind), "name=?", arrayOf(name))
        }
    }

    /** 改名；分类改名时把子分类的 parent 一起改掉 */
    fun renameLookup(kind: String, oldName: String, newName: String) {
        if (oldName == newName) return
        val table = tableOf(kind)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE $table SET name=? WHERE name=?", arrayOf(newName, oldName))
            if (kind == KIND_CATEGORY) {
                db.execSQL("UPDATE categories SET parent=? WHERE parent=?", arrayOf(newName, oldName))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 拖拽排序后按列表顺序写回 sort */
    fun setOrder(kind: String, names: List<String>) {
        val table = tableOf(kind)
        val db = writableDatabase
        db.beginTransaction()
        try {
            names.forEachIndexed { i, n ->
                db.execSQL("UPDATE $table SET sort=? WHERE name=?", arrayOf(i, n))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun tableOf(kind: String): String = when (kind) {
        KIND_ASSET -> "assets"
        KIND_CATEGORY -> "categories"
        else -> "books"
    }

    private fun namesOf(table: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM $table ORDER BY sort, name", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    // ---------------- 规则 / 日志 ----------------

    fun matchCategory(merchant: String): String? = matchRule(RULE_CATEGORY, merchant)

    /** 资产规则：付款方式文本里包含关键词就用对应资产 */
    fun matchAssetRule(paymentMethod: String?): String? {
        val t = paymentMethod?.trim().orEmpty()
        if (t.isEmpty()) return null
        return matchRule(RULE_ASSET, t)
    }

    /** 按顺序匹配用户规则；目标名对不上就跳过这条 */
    private fun matchRule(kind: String, text: String): String? {
        if (text.isBlank()) return null
        val pool = if (kind == RULE_CATEGORY) categories() else assets()
        if (pool.isEmpty()) return null
        readableDatabase.rawQuery(
            "SELECT keyword, catename FROM rules WHERE kind=? ORDER BY sort, _id",
            arrayOf(kind)
        ).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: continue
                if (k.isBlank() || !text.contains(k)) continue
                val resolved = Builtin.resolve(c.getString(1) ?: "", pool)
                if (resolved != null) return resolved
            }
        }
        return null
    }

    fun addRule(keyword: String, catename: String, kind: String = RULE_CATEGORY) {
        val next = readableDatabase.rawQuery(
            "SELECT IFNULL(MAX(sort), 0) + 1 FROM rules WHERE kind=?",
            arrayOf(kind)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }
        val v = ContentValues().apply {
            put("keyword", keyword)
            put("catename", catename)
            put("kind", kind)
            put("sort", next)
        }
        writableDatabase.insert("rules", null, v)
    }

    /** 保存拖拽后的顺序 */
    fun saveRuleOrder(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { index, id ->
                db.execSQL("UPDATE rules SET sort=? WHERE _id=?", arrayOf<Any>(index + 1, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ruleRows(kind: String = RULE_CATEGORY): List<RuleRow> {
        val out = ArrayList<RuleRow>()
        readableDatabase.rawQuery(
            "SELECT _id, keyword, catename FROM rules WHERE kind=? ORDER BY sort, _id",
            arrayOf(kind)
        ).use { c ->
            while (c.moveToNext()) out.add(RuleRow(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("rules", "_id=?", arrayOf(id.toString()))
    }

    fun updateRule(id: Long, keyword: String, catename: String) {
        val v = ContentValues().apply {
            put("keyword", keyword)
            put("catename", catename)
        }
        writableDatabase.update("rules", v, "_id=?", arrayOf(id.toString()))
    }

    fun addLog(d: Draft, status: String, detail: String): Long {
        return insertLog(
            d.pkg,
            String.format(java.util.Locale.US, "%.2f", d.logAmount ?: d.amount),
            d.merchant,
            d.logCatename ?: d.catename,
            status,
            detail,
            d.logAccount ?: d.account,
            d.type,
            d.time ?: "",
            d.coupon,
            d.account2
        )
    }

    /** 更新已有记录的发送状态（QUEUED → SENT / FAIL） */
    fun updateLogStatus(id: Long, status: String, detail: String) {
        val v = ContentValues().apply {
            put("status", status)
            put("detail", detail)
        }
        writableDatabase.update("logs", v, "_id=?", arrayOf(id.toString()))
    }

    fun insertLog(
        pkg: String,
        amount: String,
        merchant: String,
        catename: String,
        status: String,
        detail: String,
        account: String = "",
        type: Int = 0,
        billtime: String = "",
        coupon: String = "",
        account2: String = ""
    ): Long {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("pkg", pkg)
            put("amount", amount)
            put("merchant", merchant)
            put("catename", catename)
            put("status", status)
            put("detail", detail)
            put("account", account)
            put("type", type)
            put("billtime", billtime)
            put("coupon", coupon)
            put("account2", account2)
        }
        return writableDatabase.insert("logs", null, v)
    }

    data class TodaySummary(
        val count: Int,
        val amount: Double,
        val debugCount: Int,
        val pendingCount: Int,
        val failedCount: Int,
        val income: Double = 0.0
    )

    data class DayEntry(
        val id: Long,
        val ts: Long,
        val amount: String,
        val merchant: String,
        val catename: String,
        val status: String,
        val account: String,
        val type: Int,
        val pkg: String,
        val detail: String,
        val billtime: String,
        val coupon: String = "",
        val account2: String = ""
    )

    fun todaySummary(): TodaySummary {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return daySummary(start, start + 24 * 60 * 60 * 1000L)
    }

    /** 该日全部记录，按状态分别给出条数；金额只统计支出（含报销），收入单独给 */
    fun daySummary(startMs: Long, endMs: Long): TodaySummary {
        var count = 0
        var amount = 0.0
        var income = 0.0
        var debug = 0
        var pending = 0
        var failed = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*), " +
                "IFNULL(SUM(CASE WHEN status='FAIL' THEN 0 " +
                "WHEN type IN (0, 5) THEN CAST(amount AS REAL) ELSE 0 END), 0), " +
                "IFNULL(SUM(CASE WHEN status='DRYRUN' THEN 1 ELSE 0 END), 0), " +
                "IFNULL(SUM(CASE WHEN status='QUEUED' THEN 1 ELSE 0 END), 0), " +
                "IFNULL(SUM(CASE WHEN status='FAIL' THEN 1 ELSE 0 END), 0), " +
                "IFNULL(SUM(CASE WHEN status='FAIL' THEN 0 " +
                "WHEN type = 1 THEN CAST(amount AS REAL) ELSE 0 END), 0) " +
                "FROM logs WHERE ts>=? AND ts<?",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                count = c.getInt(0)
                amount = c.getDouble(1)
                debug = c.getInt(2)
                pending = c.getInt(3)
                failed = c.getInt(4)
                income = c.getDouble(5)
            }
        }
        return TodaySummary(count, amount, debug, pending, failed, income)
    }

    fun dayEntries(startMs: Long, endMs: Long): List<DayEntry> {
        val out = ArrayList<DayEntry>()
        readableDatabase.rawQuery(
            "SELECT _id, ts, amount, merchant, catename, status, IFNULL(account,''), type, pkg, IFNULL(detail,''), IFNULL(billtime,''), IFNULL(coupon,''), IFNULL(account2,'') FROM logs WHERE ts>=? AND ts<? ORDER BY ts DESC",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DayEntry(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2) ?: "",
                        c.getString(3) ?: "",
                        c.getString(4) ?: "",
                        c.getString(5) ?: "",
                        c.getString(6) ?: "",
                        c.getInt(7),
                        c.getString(8) ?: "",
                        c.getString(9) ?: "",
                        c.getString(10) ?: "",
                        c.getString(11) ?: "",
                        c.getString(12) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 同一笔账单（包名+金额+商户+账单时间）只记一次，跟浏览历史不冲突 */
    fun hasLoggedBill(pkg: String, amount: String, merchant: String, billTime: String): Boolean {
        if (billTime.isBlank()) return false
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM logs WHERE pkg=? AND amount=? AND billtime=? AND status<>'FAIL'",
            arrayOf(pkg, amount, billTime)
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) > 0
        }
    }
    /** 判重看日志表：本地记录被删掉、或上次发送失败，都应该还能再记一次 */
    fun hasRecentLog(pkg: String, amount: String, merchant: String, sinceMs: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM logs WHERE pkg=? AND amount=? AND merchant=? AND ts>=? AND status<>'FAIL'",
            arrayOf(pkg, amount, merchant, sinceMs.toString())
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) > 0
        }
    }

    fun deleteLog(id: Long) {
        writableDatabase.delete("logs", "_id=?", arrayOf(id.toString()))
    }

    /** 开发用：清空所有表并重建（资产/分类/账本/日志/规则都会没） */
    // ---------------- 备份用 ----------------

    /** 按顺序取出某个分类下的规则（keyword to target） */
    fun rulesOf(kind: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT keyword, catename FROM rules WHERE kind=? ORDER BY sort, _id", arrayOf(kind)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    fun replaceRules(kind: String, list: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("rules", "kind=?", arrayOf(kind))
            list.forEachIndexed { i, (keyword, target) ->
                db.insert("rules", null, ContentValues().apply {
                    put("keyword", keyword); put("catename", target)
                    put("kind", kind); put("sort", i); put("builtin", 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceAssets(list: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assets", null, null)
            list.forEachIndexed { i, (name, group) ->
                db.insertWithOnConflict("assets", null, ContentValues().apply {
                    put("name", name); put("sort", i)
                    put("grp", group.ifBlank { guessGroup(name) })
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceCategories(list: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("categories", null, null)
            list.forEachIndexed { i, (name, parent) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("name", name); put("parent", parent); put("sort", i)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceBooks(list: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("books", null, null)
            list.forEachIndexed { i, name ->
                db.insertWithOnConflict("books", null, ContentValues().apply {
                    put("name", name); put("sort", i)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class LogRow(
        val ts: Long,
        val pkg: String,
        val amount: String,
        val merchant: String,
        val catename: String,
        val status: String,
        val detail: String,
        val account: String,
        val type: Int,
        val billtime: String,
        val coupon: String,
        val account2: String
    )

    fun allLogs(): List<LogRow> {
        val out = ArrayList<LogRow>()
        readableDatabase.rawQuery(
            "SELECT ts, pkg, amount, merchant, catename, status, detail, account, type, billtime, " +
                "IFNULL(coupon,''), IFNULL(account2,'') FROM logs ORDER BY ts",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LogRow(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "",
                        c.getString(7) ?: "", c.getInt(8), c.getString(9) ?: "",
                        c.getString(10) ?: "", c.getString(11) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 返回是否真的插入（同一条记录不重复导） */
    fun insertLogRow(r: LogRow): Boolean {
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM logs WHERE ts=? AND pkg=? AND amount=? AND merchant=? LIMIT 1",
            arrayOf(r.ts.toString(), r.pkg, r.amount, r.merchant)
        ).use { it.moveToNext() }
        if (exists) return false
        writableDatabase.insert("logs", null, ContentValues().apply {
            put("ts", r.ts); put("pkg", r.pkg); put("amount", r.amount)
            put("merchant", r.merchant); put("catename", r.catename); put("status", r.status)
            put("detail", r.detail); put("account", r.account); put("type", r.type)
            put("billtime", r.billtime); put("coupon", r.coupon); put("account2", r.account2)
        })
        return true
    }

    /** 整表替换记账记录（导入备份时用） */
    fun replaceLogs(list: List<LogRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("logs", null, null)
            list.forEach { r ->
                db.insert("logs", null, ContentValues().apply {
                    put("ts", r.ts); put("pkg", r.pkg); put("amount", r.amount)
                    put("merchant", r.merchant); put("catename", r.catename)
                    put("status", r.status); put("detail", r.detail)
                    put("account", r.account); put("type", r.type)
                    put("billtime", r.billtime); put("coupon", r.coupon)
                    put("account2", r.account2)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun resetAll() {
        val db = writableDatabase
        for (t in listOf("rules", "logs", "assets", "categories", "books")) {
            db.execSQL("DROP TABLE IF EXISTS $t")
        }
        createAll(db)
    }

    fun debugLogCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM logs WHERE status<>'SENT'", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun clearDebugLogs(): Int {
        return writableDatabase.delete("logs", "status<>'SENT'", null)
    }

    fun logRows(limit: Int = 300): List<String> {        val out = ArrayList<String>()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
        readableDatabase.rawQuery("SELECT ts, amount, merchant, catename, status, detail FROM logs ORDER BY _id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val ts = fmt.format(java.util.Date(c.getLong(0)))
                out.add("$ts  ¥${c.getString(1)}  ${c.getString(2)}  [${c.getString(3)}]  ${c.getString(4)}  ${c.getString(5) ?: ""}")
            }
        }
        return out
    }
}
