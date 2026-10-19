package com.dsh.qianji_auto_a11y

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Collections

/** 匹配规则页：分类匹配规则 / 资产匹配规则共用 */
class RulesActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var kind: String = Store.RULE_CATEGORY

    private val builtinRows = ArrayList<RuleRow>()
    private val customRows = ArrayList<RuleRow>()
    private var builtinAdapter: RulesAdapter? = null
    private var customAdapter: RulesAdapter? = null
    private lateinit var builtinTouch: ItemTouchHelper
    private lateinit var customTouch: ItemTouchHelper

    private val isAsset get() = kind == Store.RULE_ASSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        StickyHeader.attach(this)
        store = Store(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: Store.RULE_CATEGORY

        findViewById<PageHeader>(R.id.pageHeader).apply {
            setTitle(if (isAsset) "资产匹配规则" else "分类匹配规则")
            setSubtitle(
                if (isAsset) {
                    "先匹配自定义规则，再匹配内置规则；命中即停。目标资产要已经导入过才生效。"
                } else {
                    "先匹配自定义规则，再匹配内置规则；组内从上到下，命中即停。"
                }
            )
        }
        findViewById<PageHeader>(R.id.pageHeader).addAction("恢复内置规则") { confirmRestore() }
        findViewById<TextView>(R.id.btnAddRule).setOnClickListener { showEditor(null) }

        builtinAdapter = bindList(R.id.rvBuiltin, builtinRows)
        customAdapter = bindList(R.id.rvCustom, customRows)
        reload()
    }

    private fun bindList(id: Int, data: ArrayList<RuleRow>): RulesAdapter {
        val rv = findViewById<RecyclerView>(id)
        rv.layoutManager = LinearLayoutManager(this)
        lateinit var helper: ItemTouchHelper
        val adapter = RulesAdapter(
            data,
            display = { labelFor(it) },
            onClick = { showEditor(it) },
            onLongClick = { confirmDelete(it) },
            onStartDrag = { vh -> helper.startDrag(vh) }
        )
        rv.adapter = adapter

        helper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(data, from, to)
                adapter.notifyItemMoved(from, to)
                target.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                val v = viewHolder?.itemView ?: return
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).start()
                    v.elevation = 12f * v.resources.displayMetrics.density
                    v.alpha = 0.96f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val v = viewHolder.itemView
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                v.elevation = 0f
                v.alpha = 1f
                if (data.isNotEmpty()) store.saveRuleOrder(data.map { it.id })
            }
        })
        helper.attachToRecyclerView(rv)
        return adapter
    }

    private fun reload() {
        builtinRows.clear()
        builtinRows.addAll(store.ruleRows(kind, builtin = true))
        builtinAdapter?.notifyDataSetChanged()

        customRows.clear()
        customRows.addAll(store.ruleRows(kind, builtin = false))
        customAdapter?.notifyDataSetChanged()

        findViewById<TextView>(R.id.tvBuiltinLabel).text = "内置规则（${builtinRows.size}）"
        findViewById<TextView>(R.id.tvCustomLabel).text = "自定义规则（${customRows.size}）"

        // RecyclerView 在 ScrollView 里默认只量一屏，必须显式给高度，否则后面的规则看不到
        fixListHeight(findViewById(R.id.rvBuiltin), builtinRows.size)
        fixListHeight(findViewById(R.id.rvCustom), customRows.size)

        findViewById<View>(R.id.cardBuiltin).visibility =
            if (builtinRows.isEmpty()) View.GONE else View.VISIBLE

        findViewById<View>(R.id.cardCustom).visibility =
            if (customRows.isEmpty()) View.GONE else View.VISIBLE
        findViewById<EmptyState>(R.id.tvEmpty).visibility =
            if (customRows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmRestore() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("恢复内置规则")
            .setMessage("内置规则会恢复成出厂状态，你新增的自定义规则不受影响。")
            .setPositiveButton("恢复") { _, _ ->
                store.restoreBuiltins(kind)
                reload()
                toast("已恢复内置规则")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(row: RuleRow) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("删除规则「${row.keyword} → ${displayOf(row.catename)}」？")
            .setPositiveButton("删除") { _, _ ->
                store.deleteRule(row.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 一个底部面板里完成「填关键词 + 选分类 + 保存」。
     * 编辑内置规则时保存不会改变它的「内置」身份，恢复内置规则时会一起重置。
     */
    private fun showEditor(existing: RuleRow?) {
        val all = store.categoryTree()
        val roots = all.filter { !it.isChild }
        val assets = store.assets()

        if (isAsset && assets.isEmpty()) {
            toast("还没导入资产，先回首页导入")
            return
        }
        if (!isAsset && roots.isEmpty()) {
            toast("还没导入分类，先回首页导入")
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = View.inflate(this, R.layout.sheet_rule_editor, null)
        dialog.setContentView(view)

        val sheetName = if (isAsset) "资产匹配规则" else "分类匹配规则"
        view.findViewById<TextView>(R.id.tvSheetTitle).text =
            if (existing != null) "编辑$sheetName" else "新增$sheetName"
        view.findViewById<TextView>(R.id.tvTargetLabel).text =
            if (isAsset) "资产" else "分类"

        val etKeyword = view.findViewById<EditText>(R.id.etKeyword)
        etKeyword.inputType = InputType.TYPE_CLASS_TEXT
        etKeyword.hint = if (isAsset) "付款方式关键词，如 招商" else "商户关键词，如 瑞幸"
        etKeyword.setText(existing?.keyword ?: "")

        var targetValue: String = existing?.catename ?: ""
        val left = view.findViewById<LinearLayout>(R.id.pickerLeft)
        val right = view.findViewById<LinearLayout>(R.id.pickerRight)
        val divider = view.findViewById<View>(R.id.pickerDivider)
        val leftScroll = view.findViewById<ScrollView>(R.id.pickerLeftScroll)
        val rightScroll = view.findViewById<ScrollView>(R.id.pickerRightScroll)

        if (isAsset) {
            divider.visibility = View.GONE
            rightScroll.visibility = View.GONE
            if (targetValue.isEmpty() || targetValue !in assets) targetValue = assets.first()
            fun paintAssetsCurrent() {
                left.removeAllViews()
                var selected: View? = null
                for (name in assets) {
                    val tv = rowView(name, name == targetValue)
                    if (name == targetValue) selected = tv
                    tv.setOnClickListener {
                        targetValue = name
                        paintAssetsCurrent()
                    }
                    left.addView(tv)
                }
                scrollToView(leftScroll, selected)
            }
            paintAssetsCurrent()
        } else {
            var selectedRoot = roots.firstOrNull { r ->
                targetValue == r.name || targetValue.startsWith("${r.name}/::/")
            } ?: roots.first()
            if (targetValue.isEmpty()) targetValue = selectedRoot.name

            fun paint() {
                left.removeAllViews()
                var selectedLeft: View? = null
                for (root in roots) {
                    val tv = rowView(root.name, root.name == selectedRoot.name)
                    if (root.name == selectedRoot.name) selectedLeft = tv
                    tv.setOnClickListener {
                        selectedRoot = root
                        if (targetValue.isEmpty() || !targetValue.startsWith("${root.name}")) {
                            targetValue = root.name
                        }
                        paint()
                    }
                    left.addView(tv)
                }
                right.removeAllViews()
                var selectedRight: View? = null
                val whole = rowView("（整个分类）", targetValue == selectedRoot.name)
                if (targetValue == selectedRoot.name) selectedRight = whole
                whole.setOnClickListener {
                    targetValue = selectedRoot.name
                    paint()
                }
                right.addView(whole)
                for (kid in all.filter { it.isChild && it.parent == selectedRoot.name }) {
                    val value = "${selectedRoot.name}/::/${kid.name}"
                    val tv = rowView(kid.name, targetValue == value)
                    if (targetValue == value) selectedRight = tv
                    tv.setOnClickListener {
                        targetValue = value
                        paint()
                    }
                    right.addView(tv)
                }
                scrollToView(leftScroll, selectedLeft)
                scrollToView(rightScroll, selectedRight)
            }
            paint()
        }

        view.findViewById<TextView>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val keyword = etKeyword.text.toString().trim()
            if (keyword.isEmpty() || targetValue.isEmpty()) {
                toast("关键词和分类都要选")
                return@setOnClickListener
            }
            if (existing == null) {
                store.addRule(keyword, targetValue, kind)
            } else {
                store.updateRule(existing.id, keyword, targetValue)
            }
            dialog.dismiss()
            reload()
        }

        // 面板本身不跟手拖动，否则内层列表滑到底再往下拉会把面板关掉
        dialog.behavior.isDraggable = false
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /** 打开时把已选中的那一行滚到可视区域中间 */
    private fun scrollToView(scroll: ScrollView, target: View?) {
        if (target == null) return
        scroll.post {
            val y = target.top - (scroll.height - target.height) / 2
            scroll.scrollTo(0, y.coerceAtLeast(0))
        }
    }

    /** 每行 56dp，按条数把 RecyclerView 撑开，避免只量出首屏 */
    private fun fixListHeight(rv: RecyclerView, count: Int) {
        val lp = rv.layoutParams
        lp.height = dp(56) * count
        rv.layoutParams = lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 目标分类/资产没导入时给个提示，避免以为规则生效了 */
    private fun labelFor(value: String): String {
        val pool = if (isAsset) store.assets() else store.categories()
        val ok = Builtin.resolve(value, pool) != null
        return if (ok) displayOf(value) else "${displayOf(value)}（未导入）"
    }

    private fun displayOf(value: String): String {
        if (value.isBlank()) return ""
        val parts = value.split("/::/")
        return if (parts.size == 2) "${parts[0]} / ${parts[1]}" else value
    }

    private fun rowView(text: String, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
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

    companion object {
        const val EXTRA_KIND = "ruleKind"
    }
}
