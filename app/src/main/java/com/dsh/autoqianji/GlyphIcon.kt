package com.dsh.autoqianji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs

/**
 * 分类图标：圆形色块 + 名称首字。
 * 这也是钱迹在「分类没有自定义图标」时的渲染方式，风格一致且不占资源。
 */
object GlyphIcon {

    private val palette = intArrayOf(
        0xFF4A6CF7.toInt(), 0xFFF97316.toInt(), 0xFF22C55E.toInt(), 0xFFEF4444.toInt(),
        0xFFA855F7.toInt(), 0xFF06B6D4.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(),
        0xFF10B981.toInt(), 0xFF6366F1.toInt(), 0xFF84CC16.toInt(), 0xFF14B8A6.toInt()
    )

    private val cache = HashMap<String, Drawable>()

    fun forName(context: Context, name: String, sizePx: Int): Drawable {
        val key = "$name@$sizePx"
        cache[key]?.let { return it }

        val size = sizePx.coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette[abs(name.hashCode()) % palette.size]
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)

        val text = label(name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (text.length <= 1) size * 0.46f else size * 0.34f
        }
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)

        val drawable = BitmapDrawable(context.resources, bmp)
        cache[key] = drawable
        return drawable
    }

    private fun label(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) return "?"
        val first = clean[0]
        if (first.code < 0x2E80) return first.uppercaseChar().toString()
        return first.toString()
    }
}
