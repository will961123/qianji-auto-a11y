package com.dsh.qianji_auto_a11y

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.setPadding

/** 通用列表管理页：资产 / 分类 / 账本共用，支持分组新增、拖动排序、左滑删除 */
class LookupActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var adapter: LookupAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var kind: String = Store.KIND_ASSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lookup)
        StickyHeader.attach(this)
        store = Store(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: Store.KIND_ASSET

        val title: String
        val sub: String
        when (kind) {
            Store.KIND_CATEGORY -> {
                title = "分类管理"
                sub = "一级分类是分组，分组标题右侧 ＋ 可加二级；底部按钮新增一级分类。"
            }
            Store.KIND_BOOK -> {
                title = "账本管理"
                sub = "记账时作为 bookname 传给钱迹；留空则用默认账本。"
            }
            else -> {
                title = "资产管理"
                sub = "分「信用卡 / 资产」两组。付款方式按名字匹配到这里，例如「中信银行信用卡(1278)」→「中信银行信用卡」。"
            }
        }
        findViewById<PageHeader>(R.id.pageHeader).apply {
            setTitle(title)
            setSubtitle("$sub\n长按条目可编辑 / 删除，拖动右侧把手排序。")
        }

        findViewById<View>(R.id.btnAddRoot).apply {
            visibility = if (kind == Store.KIND_CATEGORY) View.VISIBLE else View.GONE
            setOnClickListener { promptAdd("", "一级分类") }
        }

        val rv = findViewById<RecyclerView>(R.id.rvItems)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = LookupAdapter(
            onStartDrag = { vh -> touchHelper.startDrag(vh) },
            onAddToGroup = { row -> promptAdd(row.groupKey, row.name) },
            onItemMenu = { row, anchor -> showItemMenu(row, anchor) }
        )
        rv.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            /** 长按不拖：拖拽只从右侧把手发起，长按留给「编辑 / 删除」菜单 */
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (viewHolder.itemViewType == LookupAdapter.TYPE_HEADER) return 0
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val a = adapter.rowAt(from)
                val b = adapter.rowAt(to)
                if (a.header || b.header || a.group != b.group) return false
                adapter.move(from, to)
                target.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

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
                store.setOrder(kind, adapter.names())
            }
        })
        touchHelper.attachToRecyclerView(rv)

        reload()
    }

    private fun promptAdd(groupKey: String, groupLabel: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = when {
                kind == Store.KIND_CATEGORY && groupKey.isEmpty() -> "新的一级分类名"
                kind == Store.KIND_CATEGORY -> "「$groupLabel」下的二级分类名"
                kind == Store.KIND_ASSET -> "新资产名"
                else -> "新账本名"
            }
        }
        input.setPadding(dp(20))
        AlertDialog.Builder(this)
            .setTitle(if (groupLabel.isBlank()) "新增" else "添加到「$groupLabel」")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                store.addLookup(kind, name, groupKey)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showItemMenu(row: LookupAdapter.Row, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "编辑")
        if (kind == Store.KIND_CATEGORY && row.groupKey.isNotEmpty() && row.deletable) {
            popup.menu.add(0, 3, 1, "在此分类下新增二级分类")
        }
        popup.menu.add(0, 2, 2, "删除")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptRename(row)
                3 -> promptAdd(row.groupKey, row.name)
                2 -> confirmDelete(row)
            }
            true
        }
        popup.show()
    }

    private fun promptRename(row: LookupAdapter.Row) {
        if (isProtectedRoot(row)) {
            android.widget.Toast.makeText(this, "「${row.name}」是内置分类，不能改名", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(row.name)
            setSelection(row.name.length)
        }
        input.setPadding(dp(20))
        AlertDialog.Builder(this)
            .setTitle("编辑「${row.name}」")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.renameLookup(kind, row.name, name)
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 内置分类（其它）本身不可删，里面的二级分类照常可以删 */
    private fun isProtectedRoot(row: LookupAdapter.Row): Boolean =
        kind == Store.KIND_CATEGORY && row.header && row.deletable &&
            row.name in Builtin.protectedCategories

    private fun confirmDelete(row: LookupAdapter.Row) {
        if (isProtectedRoot(row)) {
            android.widget.Toast.makeText(this, "「${row.name}」是内置分类，不能删除", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val kids = if (kind == Store.KIND_CATEGORY) {
            Store(this).categoryTree().count { it.isChild && it.parent == row.name }
        } else 0
        val message = if (kids > 0) {
            "删除「${row.name}」？\n\n它下面的 $kids 个二级分类也会一起删除。"
        } else {
            "删除「${row.name}」？"
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                store.deleteLookup(kind, row.name)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun reload() {
        val rows = ArrayList<LookupAdapter.Row>()
        val px = (40 * resources.displayMetrics.density).toInt()

        when (kind) {
            Store.KIND_CATEGORY -> {
                // 新增一级分类的按钮固定在页面底部，列表里不再放小标题
                val tree = store.categoryTree()
                val roots = tree.filter { !it.isChild }
                var group = 0
                for (root in roots) {
                    group++
                    rows.add(
                        LookupAdapter.Row(
                            root.name, GlyphIcon.forName(this, root.name, px), false,
                            header = true, group = group, groupKey = root.name,
                            addLabel = "在「${root.name}」下新增", deletable = true
                        )
                    )
                    val kids = tree.filter { it.isChild && it.parent == root.name }
                    if (kids.isEmpty()) {
                        rows.add(
                            LookupAdapter.Row(
                                "还没有二级分类", null, false,
                                placeholder = true, group = group
                            )
                        )
                    } else {
                        for (kid in kids) {
                            rows.add(
                                LookupAdapter.Row(
                                    kid.name, GlyphIcon.forName(this, kid.name, px), true,
                                    group = group
                                )
                            )
                        }
                    }
                }
            }

            Store.KIND_BOOK -> {
                rows.add(
                    LookupAdapter.Row("账本", null, false, header = true, group = 0, groupKey = "", addLabel = "新增账本")
                )
                val books = store.books()
                if (books.isEmpty()) {
                    rows.add(LookupAdapter.Row("还没有账本", null, false, placeholder = true, group = 1))
                } else for (b in books) {
                    rows.add(LookupAdapter.Row(b, GlyphIcon.forName(this, b, px), false, group = 1))
                }
            }

            else -> {
                val cards = ArrayList<String>()
                val assets = ArrayList<String>()
                for ((name, grp) in store.assetsWithGroup()) {
                    if (grp == Store.GRP_CREDIT) cards.add(name) else assets.add(name)
                }

                rows.add(
                    LookupAdapter.Row(
                        "信用卡", null, false,
                        header = true, group = 0, groupKey = Store.GRP_CREDIT, addLabel = "新增信用卡"
                    )
                )
                if (cards.isEmpty()) {
                    rows.add(LookupAdapter.Row("还没有信用卡", null, false, placeholder = true, group = 1))
                } else cards.forEach {
                    rows.add(LookupAdapter.Row(it, GlyphIcon.forName(this, it, px), false, group = 1))
                }

                rows.add(
                    LookupAdapter.Row(
                        "资产", null, false,
                        header = true, group = 0, groupKey = Store.GRP_ASSET, addLabel = "新增资产"
                    )
                )
                if (assets.isEmpty()) {
                    rows.add(LookupAdapter.Row("还没有资产", null, false, placeholder = true, group = 2))
                } else assets.forEach {
                    rows.add(LookupAdapter.Row(it, GlyphIcon.forName(this, it, px), false, group = 2))
                }
            }
        }

        adapter.submit(rows)
        val empty = rows.none { !it.header && !it.placeholder }
        findViewById<EmptyState>(R.id.emptyLookup).visibility =
            if (empty) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvItems).visibility =
            if (empty) android.view.View.GONE else android.view.View.VISIBLE
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
