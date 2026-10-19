package com.dsh.qianji_auto_a11y

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 兜底参数：默认资产 / 默认账本 / 默认分类 */
class DefaultsActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var card: LinearLayout

    private companion object {
        const val NONE = "（不设置）"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_defaults)
        StickyHeader.attach(this)
        store = Store(this)
        card = findViewById(R.id.cardDefaults)
        render()
    }

    private fun render() {
        card.removeAllViews()

        row(
            icon = R.drawable.ic_line_wallet,
            title = "默认资产",
            value = Config.defaultAccount(this).ifBlank { "未设置" }
        ) {
            val assets = store.assets()
            if (assets.isEmpty()) {
                Toast.makeText(this, "还没导入资产，请在「设置 → 从钱迹导入」导入", Toast.LENGTH_SHORT).show()
                return@row
            }
            val options = listOf(NONE) + assets
            pick("选择默认资产", options, Config.defaultAccount(this).ifBlank { NONE }) { picked ->
                Config.setDefaultAccount(this, if (picked == NONE) "" else picked)
                render()
            }
        }


        row(
            icon = R.drawable.ic_line_list,
            title = "默认分类",
            value = displayOf(Config.defaultCategory(this)).ifBlank { "其它" }
        ) {
            pickCategory()
        }

        row(
            icon = R.drawable.ic_line_book,
            title = "默认账本",
            value = Config.book(this).ifBlank { "未设置" }
        ) {
            val books = store.books()
            if (books.isEmpty()) {
                Toast.makeText(this, "还没导入账本，请在「设置 → 从钱迹导入」导入", Toast.LENGTH_SHORT).show()
                return@row
            }
            val options = listOf(NONE) + books
            pick("选择默认账本", options, Config.book(this).ifBlank { NONE }) { picked ->
                Config.setBook(this, if (picked == NONE) "" else picked)
                render()
            }
        }

    }

    private fun row(icon: Int, title: String, value: String, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, card, false)
        v.findViewById<ImageView>(R.id.ivSettingIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tvSettingTitle).text = title
        v.findViewById<TextView>(R.id.tvSettingSub).text = value
        v.findViewById<TextView>(R.id.tvSettingSub).visibility = View.VISIBLE
        v.setOnClickListener { onClick() }
        card.addView(v)
    }

    /** 资产 / 账本：单列选择，占位项表示不设置 */
    private fun pick(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }

        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(20), 0, dp(20), dp(8))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(
            list,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        root.addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, dp(280))

        var selectedView: View? = null
        for (option in options) {
            val tv = rowView(option, option == current)
            if (option == current) selectedView = tv
            tv.setOnClickListener {
                onPick(option)
                dialog.dismiss()
            }
            list.addView(tv)
        }
        scrollToView(scroll, selectedView)

        dialog.setContentView(root)
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    /** 默认分类：一级 / 二级两级选择 */
    private fun pickCategory() {
        val all = store.categoryTree()
        val roots = all.filter { !it.isChild }
        if (roots.isEmpty()) {
            Toast.makeText(this, "还没导入分类，请在「设置 → 从钱迹导入」导入", Toast.LENGTH_SHORT).show()
            return
        }

        val current = Config.defaultCategory(this)
        val dialog = BottomSheetDialog(this)
        val view = View.inflate(this, R.layout.sheet_category, null)
        dialog.setContentView(view)

        val left = view.findViewById<LinearLayout>(R.id.catLeft)
        val right = view.findViewById<LinearLayout>(R.id.catRight)
        val leftScroll = view.findViewById<ScrollView>(R.id.catLeftScroll)
        val rightScroll = view.findViewById<ScrollView>(R.id.catRightScroll)

        var selectedRoot = roots.firstOrNull { r ->
            current == r.name || current.startsWith("${r.name}/::/")
        } ?: roots.first()

        fun choose(value: String) {
            Config.setDefaultCategory(this, value)
            dialog.dismiss()
            render()
        }

        fun paint() {
            left.removeAllViews()
            var selectedLeft: View? = null
            for (root in roots) {
                val tv = rowView(root.name, root.name == selectedRoot.name)
                if (root.name == selectedRoot.name) selectedLeft = tv
                tv.setOnClickListener {
                    selectedRoot = root
                    paint()
                }
                left.addView(tv)
            }

            right.removeAllViews()
            var selectedRight: View? = null
            val whole = rowView("（整个分类）", current == selectedRoot.name)
            if (current == selectedRoot.name) selectedRight = whole
            whole.setOnClickListener { choose(selectedRoot.name) }
            right.addView(whole)

            for (kid in all.filter { it.isChild && it.parent == selectedRoot.name }) {
                val value = "${selectedRoot.name}/::/${kid.name}"
                val tv = rowView(kid.name, current == value)
                if (current == value) selectedRight = tv
                tv.setOnClickListener { choose(value) }
                right.addView(tv)
            }

            scrollToView(leftScroll, selectedLeft)
            scrollToView(rightScroll, selectedRight)
        }

        val clear = rowView("清除默认分类（记为「其它」）", current.isBlank())
        clear.setOnClickListener { choose("") }
        (view as LinearLayout).addView(clear)

        paint()
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    private fun displayOf(value: String): String {
        if (value.isBlank()) return ""
        val parts = value.split("/::/")
        return if (parts.size == 2) "${parts[0]} / ${parts[1]}" else value
    }

    private fun scrollToView(scroll: ScrollView, target: View?) {
        if (target == null) return
        scroll.post {
            val y = target.top - (scroll.height - target.height) / 2
            scroll.scrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun rowView(text: String, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(20), dp(14), dp(20), dp(14))
            gravity = Gravity.CENTER_VERTICAL
                        setTextColor(
                if (selected) androidx.core.content.ContextCompat.getColor(context, R.color.accent)
                else androidx.core.content.ContextCompat.getColor(context, R.color.text_primary)
            )
            if (selected) {
                setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF or 0x33000000
                )
                setTypeface(null, Typeface.BOLD)
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
