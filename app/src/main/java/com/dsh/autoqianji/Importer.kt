package com.dsh.autoqianji

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 解析钱迹「数据导出」生成的文件，提取资产名、分类（一级/二级）、账本名。
 *
 * 支持 xlsx（导出默认）/ json / csv（含 GBK 兜底）。
 */
object Importer {

    /** categories 的每个元素是 (名称, 父分类)；父分类为 null 表示一级分类 */
    data class Result(
        val assets: List<String>,
        val categories: List<Pair<String, String?>>,
        val books: List<String>
    )

    private val ASSET_KEY = Regex("(?i)(asset|account|资产|账户|账号)")
    private val CATE_KEY = Regex("(?i)(categor|分类|类别)")
    private val SUB_CATE_KEY = Regex("(?i)(sub|二级|子分类)")
    private val BOOK_KEY = Regex("(?i)(book|账本)")

    /** 「其它/其他」是钱迹里真实存在的一级分类，不能当脏数据跳过 */
    private val SKIP_WORDS = setOf(
        "", "无", "null", "NULL", "未知", "总计", "合计", "小计"
    )

    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return try {
            String(bytes, charset("GBK"))
        } catch (e: Exception) {
            utf8
        }
    }

    fun parse(bytes: ByteArray): Result {
        if (XlsxReader.isXlsx(bytes)) return parseRows(XlsxReader.readRows(bytes))
        val text = decode(bytes).trim().removePrefix("\uFEFF").trim()
        return when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text)
            else -> parseCsv(text)
        }
    }

    // ---------------- 表格 ----------------

    private fun parseRows(rows: List<List<String>>): Result {
        if (rows.isEmpty()) return empty()
        val headerIdx = rows.take(8).indexOfFirst { row ->
            row.any { ASSET_KEY.containsMatchIn(it) || CATE_KEY.containsMatchIn(it) || BOOK_KEY.containsMatchIn(it) }
        }
        if (headerIdx < 0) return empty()

        val header = rows[headerIdx]
        val assetCols = header.indices.filter { ASSET_KEY.containsMatchIn(header[it]) }
        val cateCols = header.indices.filter { CATE_KEY.containsMatchIn(header[it]) }
        val subCols = cateCols.filter { SUB_CATE_KEY.containsMatchIn(header[it]) }
        val mainCols = cateCols.filter { it !in subCols }
        val bookCols = header.indices.filter { BOOK_KEY.containsMatchIn(header[it]) }

        val assets = LinkedHashSet<String>()
        val cats = LinkedHashSet<Pair<String, String?>>()
        val books = LinkedHashSet<String>()

        for (row in rows.drop(headerIdx + 1)) {
            for (i in assetCols) row.getOrNull(i)?.trim()?.let { if (valid(it)) assets.add(it) }
            for (i in bookCols) row.getOrNull(i)?.trim()?.let { if (valid(it)) books.add(it) }

            val parent = mainCols.firstNotNullOfOrNull { i -> row.getOrNull(i)?.trim()?.takeIf { valid(it) } }
            val child = subCols.firstNotNullOfOrNull { i -> row.getOrNull(i)?.trim()?.takeIf { valid(it) } }

            if (parent != null) cats.add(parent to null)
            if (child != null) {
                cats.add(child to parent)
                if (parent == null) cats.add(child to null)
            }
        }
        return Result(assets.toList(), cats.toList(), books.toList())
    }

    private fun parseCsv(text: String): Result {
        val lines = text.split('\n', '\r').filter { it.isNotBlank() }
        if (lines.isEmpty()) return empty()
        return parseRows(lines.map { splitCsvLine(it) })
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuote = !inQuote
                    }
                }
                (c == ',' || c == '\t') && !inQuote -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ---------------- JSON ----------------

    private fun parseJson(text: String): Result {
        val assets = LinkedHashSet<String>()
        val cats = LinkedHashSet<Pair<String, String?>>()
        val books = LinkedHashSet<String>()
        try {
            walk(JSONTokener(text).nextValue(), assets, cats, books)
        } catch (_: Exception) {
        }
        return Result(assets.toList(), cats.toList(), books.toList())
    }

    private fun walk(
        value: Any?,
        assets: MutableSet<String>,
        cats: MutableSet<Pair<String, String?>>,
        books: MutableSet<String>
    ) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val v = value.opt(key)
                    if (ASSET_KEY.containsMatchIn(key)) collect(v, assets)
                    if (CATE_KEY.containsMatchIn(key)) collectCats(v, cats)
                    if (BOOK_KEY.containsMatchIn(key)) collect(v, books)
                    walk(v, assets, cats, books)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), assets, cats, books)
        }
    }

    private fun collect(value: Any?, into: MutableSet<String>) {
        when (value) {
            is String -> if (valid(value.trim())) into.add(value.trim())
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), into)
            is JSONObject -> {
                for (nameKey in listOf("name", "名称", "title")) {
                    val s = value.optString(nameKey, "")
                    if (s.isNotBlank() && valid(s.trim())) {
                        into.add(s.trim())
                        break
                    }
                }
            }
        }
    }

    private fun collectCats(value: Any?, into: MutableSet<Pair<String, String?>>) {
        when (value) {
            is String -> if (valid(value.trim())) into.add(value.trim() to null)
            is JSONArray -> for (i in 0 until value.length()) collectCats(value.opt(i), into)
            is JSONObject -> {
                for (nameKey in listOf("name", "名称", "title")) {
                    val s = value.optString(nameKey, "")
                    if (s.isNotBlank() && valid(s.trim())) {
                        into.add(s.trim() to null)
                        break
                    }
                }
            }
        }
    }

    private fun valid(v: String): Boolean {
        if (v.isBlank() || v.length > 30) return false
        if (v in SKIP_WORDS) return false
        if (v.toDoubleOrNull() != null) return false
        return v.any { it.code in 0x4E00..0x9FFF || it.isLetter() }
    }

    private fun empty() = Result(emptyList(), emptyList(), emptyList())
}
