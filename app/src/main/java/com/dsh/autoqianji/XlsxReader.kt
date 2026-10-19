package com.dsh.autoqianji

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取：只做「把第一个工作表读成二维字符串表」这一件事，
 * 不依赖 Apache POI（那玩意儿十几 MB）。
 * xlsx 本质是个 zip，里面是 sharedStrings.xml + sheetN.xml。
 */
object XlsxReader {

    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    fun readRows(bytes: ByteArray): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "xl/sharedStrings.xml" ||
                        (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                    ) {
                        parts[name] = zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetKey = parts.keys
            .filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it } ?: return emptyList()
        return parts[sheetKey]?.let { parseSheet(it, shared) } ?: emptyList()
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType
            var inSi = false
            val sb = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "si" -> {
                                inSi = true
                                sb.setLength(0)
                            }
                            "t" -> if (inSi) sb.append(parser.nextText())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "si") {
                            out.add(sb.toString())
                            inSi = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var row: ArrayList<String>? = null
            var col = 0
            var type = ""
            var value: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> {
                            row = ArrayList()
                            col = 0
                        }
                        "c" -> {
                            type = parser.getAttributeValue(null, "t") ?: ""
                            col = columnIndex(parser.getAttributeValue(null, "r"))
                            value = null
                        }
                        "v" -> value = parser.nextText()
                        "t" -> if (type == "inlineStr") value = (value ?: "") + parser.nextText()
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "c" -> {
                            val cell = when (type) {
                                "s" -> value?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                else -> value ?: ""
                            }
                            val r = row ?: ArrayList<String>().also { row = it }
                            while (r.size < col) r.add("")
                            if (r.size == col) r.add(cell) else r[col] = cell
                        }
                        "row" -> {
                            row?.let { rows.add(it) }
                            row = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return rows
    }

    /** "B12" -> 1 */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) {
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return (n - 1).coerceAtLeast(0)
    }
}
