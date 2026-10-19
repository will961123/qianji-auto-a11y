package com.dsh.autoqianji

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 二级页统一的标题头：大标题 + 右侧操作按钮 + 副标题。
 * 用法：layout 里写
 *   <com.dsh.autoqianji.PageHeader
 *       android:id="@+id/pageHeader"
 *       app:headerTitle="记账记录"
 *       app:headerSubtitle="按天查看" />
 * 需要右侧按钮时在代码里 header.addAction("新增") { ... }。
 */
class PageHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val titleView: TextView
    private val subtitleView: TextView
    private val actions: LinearLayout

    val title: CharSequence
        get() = titleView.text

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_page_header, this, true)
        titleView = findViewById(R.id.tvHeaderTitle)
        subtitleView = findViewById(R.id.tvHeaderSubtitle)
        actions = findViewById(R.id.headerActions)

        findViewById<View>(R.id.ivHeaderBack)?.setOnClickListener {
            val act = context as? androidx.activity.ComponentActivity ?: return@setOnClickListener
            act.onBackPressedDispatcher.onBackPressed()
        }

        val a = context.obtainStyledAttributes(attrs, R.styleable.PageHeader)
        val t = a.getString(R.styleable.PageHeader_headerTitle)
        val s = a.getString(R.styleable.PageHeader_headerSubtitle)
        a.recycle()

        setTitle(t ?: "")
        setSubtitle(s)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setSubtitle(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = text
        }
    }

    /** 右侧文字按钮，样式和应用里其他"批量管理 / 新增"一致 */
    fun addAction(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = (8 * resources.displayMetrics.density).toInt()
            val padV = (6 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = context.getDrawable(android.R.drawable.list_selector_background)
            isClickable = true
            setOnClickListener { onClick() }
        }
        actions.addView(tv)
        return tv
    }

    /** 供折叠后的小标题使用 */
    fun titleText(): String = titleView.text?.toString() ?: ""
}
