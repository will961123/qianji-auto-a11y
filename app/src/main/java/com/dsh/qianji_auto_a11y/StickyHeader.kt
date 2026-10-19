package com.dsh.qianji_auto_a11y

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 让二级页在滚动时出现吸顶标题。
 * 做法：把 Activity 的内容包一层 FrameLayout，在最上面叠一条 56dp 的窄标题条，
 * 滚动超过标题高度后淡入小标题，等于把大标题"收起"。
 */
object StickyHeader {

    private fun findTitle(activity: Activity, content: ViewGroup): String? {
        val queue = ArrayDeque<View>()
        queue.add(content)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView && v.textSize >= 20f && v.text.isNotEmpty()) {
                return v.text.toString()
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    private fun findScroll(content: ViewGroup): ScrollView? {
        val queue = ArrayDeque<View>()
        queue.add(content)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /** 吸顶条要压在状态栏下方，不然会被时间/电量盖住 */
    private fun statusBarHeight(activity: Activity): Int {
        val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) activity.resources.getDimensionPixelSize(resId) else 0
    }

    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // 标题：优先用 PageHeader，老页面回退到 id=tvTitle 或第一个 22sp 的 TextView
        val header = activity.findViewById<PageHeader>(R.id.pageHeader)
        val titleText = header?.titleText() ?: findTitle(activity, content) ?: return

        // 滚动容器：优先 pageScroll（必须能转成 ScrollView），回退到内容里的第一个 ScrollView
        val scroll = activity.findViewById<View>(R.id.pageScroll) as? ScrollView
            ?: findScroll(content) ?: return

        val page = content.getChildAt(0) ?: return
        if (page.parent is FrameLayout && (page.parent as FrameLayout).id == R.id.stickyWrapper) return

        val density = activity.resources.displayMetrics.density

        // 吸顶条：返回箭头固定左侧，标题居中；标题随滚动从左侧滑到中间
        val titleView = TextView(activity).apply {
            text = titleText
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_primary))
            gravity = Gravity.CENTER
        }
        val titleSlot = FrameLayout(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
            addView(
                titleView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            )
        }

        val bar = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            ).apply {
                gravity = Gravity.TOP
                topMargin = statusBarHeight(activity)
            }
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(activity, R.color.app_bg))
            val padH = (20 * density).toInt()
            setPadding(padH, 0, padH, 0)
            alpha = 0f
            visibility = View.GONE

            val back = android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron_left)
                val s = (28 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(s, s)
                setOnClickListener {
                    (activity as? androidx.activity.ComponentActivity)
                        ?.onBackPressedDispatcher?.onBackPressed()
                }
            }
            addView(back)
            addView(titleSlot)
            // 右侧补一个和箭头等宽的占位，标题才是真正居中
            addView(View(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (28 * density).toInt(),
                    1
                )
            })
        }

        // 标题从左侧滑入的位移量：居中位置到左侧起始位置的距离
        var titleShift = 0f
        titleSlot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val slotW = titleSlot.width
            val textW = titleView.width
            if (slotW > 0 && textW > 0) titleShift = (slotW - textW) / 2f
        }

        content.removeView(page)
        val wrapper = FrameLayout(activity).apply {
            id = R.id.stickyWrapper
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        wrapper.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        wrapper.addView(bar)
        content.addView(wrapper)

        // 大标题的折叠阈值：滚过标题块高度就完全显示吸顶条
        val headerHeight = header?.height ?: (72 * density).toInt()
        val threshold = headerHeight.takeIf { it > 0 }?.toFloat() ?: (72 * density).toFloat()

        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val ratio = (scrollY / threshold).coerceIn(0f, 1f)
            bar.alpha = ratio
            bar.visibility = if (ratio <= 0.01f) View.GONE else View.VISIBLE
            // 标题从左侧起点滑到居中位置
            titleView.translationX = -titleShift * (1f - ratio)
        }
    }
}
