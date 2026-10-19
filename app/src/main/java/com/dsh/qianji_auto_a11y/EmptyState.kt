package com.dsh.qianji_auto_a11y

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 统一空状态：图标 + 标题 + 说明。
 * layout 里：
 *   <com.dsh.qianji_auto_a11y.EmptyState
 *       android:id="@+id/emptyState"
 *       app:emptyIcon="@drawable/ic_line_folder"
 *       app:emptyTitle="还没有自定义规则"
 *       app:emptyHint="没命中的商户会用内置规则" />
 */
class EmptyState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val iconView: ImageView
    private val titleView: TextView
    private val hintView: TextView

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_empty_state, this, true)
        iconView = findViewById(R.id.ivEmptyIcon)
        titleView = findViewById(R.id.tvEmptyTitle)
        hintView = findViewById(R.id.tvEmptyHint)

        val a = context.obtainStyledAttributes(attrs, R.styleable.EmptyState)
        val icon = a.getResourceId(R.styleable.EmptyState_emptyIcon, 0)
        val title = a.getString(R.styleable.EmptyState_emptyTitle)
        val hint = a.getString(R.styleable.EmptyState_emptyHint)
        a.recycle()

        if (icon != 0) iconView.setImageResource(icon)
        setTitle(title ?: "")
        setHint(hint ?: "")
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
        titleView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setHint(text: CharSequence) {
        hintView.text = text
        hintView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setIcon(resId: Int) = iconView.setImageResource(resId)
}
