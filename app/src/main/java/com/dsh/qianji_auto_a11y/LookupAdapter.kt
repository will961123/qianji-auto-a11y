package com.dsh.qianji_auto_a11y

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LookupAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onAddToGroup: (Row) -> Unit,
    private val onItemMenu: (Row, View) -> Unit
) : RecyclerView.Adapter<LookupAdapter.VH>() {

    /** group 相同的条目之间才允许拖动互换；header 行的 groupKey 决定「＋」加到哪 */
    data class Row(
        val name: String,
        val drawable: Drawable?,
        val indented: Boolean,
        val header: Boolean = false,
        val group: Int = 0,
        val groupKey: String = "",
        val addLabel: String = "",
        /** 一级分类这种「分组标题形态但本身是数据」的行，允许长按编辑/删除 */
        val deletable: Boolean = false,
        /** 组内没有数据时的占位行 */
        val placeholder: Boolean = false
    )

    private val items = ArrayList<Row>()

    fun submit(list: List<Row>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): Row = items[position]

    fun names(): List<String> = items.filter { !it.header }.map { it.name }

    fun move(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_lookup, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val density = holder.itemView.resources.displayMetrics.density
        val startPad = 0
        val endPad = (4 * density).toInt()
        val isLast = position == items.size - 1
        val nextIsHeader = !isLast && items[position + 1].header
        holder.divider.visibility =
            if (isLast || nextIsHeader || row.header) View.GONE else View.VISIBLE

        // 每组一个卡片：首行上圆角、末行下圆角、中间直角
        val prevIsHeader = position == 0 || items[position - 1].header
        val firstInGroup = prevIsHeader
        val lastInGroup = isLast || nextIsHeader
        if (!row.header) {
            val bg = when {
                firstInGroup && lastInGroup -> R.drawable.bg_row_single
                firstInGroup -> R.drawable.bg_row_top
                lastInGroup -> R.drawable.bg_row_bottom
                else -> R.drawable.bg_row_middle
            }
            holder.itemView.setBackgroundResource(bg)
        }

        if (row.header) {
            // 分组标题：卡片外面的小标题，带 ＋
            holder.itemView.minimumHeight = 0
            holder.content.minimumHeight = 0
            holder.content.setPaddingRelative(0, 0, 0, 0)
            holder.itemView.setBackgroundColor(0x00000000)
            holder.itemView.setPaddingRelative(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (6 * density).toInt()
            )
            (holder.name.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 0
            holder.icon.visibility = View.GONE
            holder.handle.visibility = View.GONE
            holder.name.text = row.name
            holder.name.textSize = 12f
            holder.name.alpha = 1f
            holder.name.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            holder.name.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    holder.itemView.context, R.color.text_secondary
                )
            )
            holder.name.setTypeface(null, Typeface.NORMAL)
            holder.handle.setOnTouchListener(null)
            if (row.deletable) {
                holder.itemView.isLongClickable = true
                holder.itemView.setOnLongClickListener {
                    onItemMenu(row, holder.itemView)
                    true
                }
            } else {
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isLongClickable = false
            }
            if (row.addLabel.isNotEmpty()) {
                holder.addLabel.visibility = View.VISIBLE
                holder.addLabel.contentDescription = row.addLabel
                holder.addLabel.setOnClickListener { onAddToGroup(row) }
            } else {
                holder.addLabel.visibility = View.GONE
                holder.addLabel.setOnClickListener(null)
            }
            holder.add.visibility = View.GONE
            holder.add.setOnClickListener(null)
            return
        }

        if (row.placeholder) {
            holder.itemView.minimumHeight = 0
            holder.content.minimumHeight = 0
            holder.content.setPaddingRelative(0, 0, 0, 0)
            holder.itemView.setBackgroundResource(R.drawable.bg_row_single)
            holder.itemView.setPaddingRelative(0, (26 * density).toInt(), 0, (26 * density).toInt())
            holder.icon.visibility = View.GONE
            holder.handle.visibility = View.GONE
            holder.add.visibility = View.GONE
            holder.addLabel.visibility = View.GONE
            holder.divider.visibility = View.GONE
            holder.name.text = row.name
            holder.name.textSize = 12f
            holder.name.alpha = 1f
            holder.name.gravity = android.view.Gravity.CENTER
            holder.name.setTypeface(null, Typeface.NORMAL)
            holder.name.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    holder.itemView.context, R.color.text_secondary
                )
            )
            (holder.name.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 0
            holder.itemView.isLongClickable = false
            holder.itemView.setOnLongClickListener(null)
            return
        }
        holder.itemView.minimumHeight = (56 * density).toInt()
        holder.content.minimumHeight = (56 * density).toInt()
        holder.content.setPaddingRelative(0, 0, 0, 0)
        holder.name.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        holder.name.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context, R.color.text_primary
            )
        )

        val indent = 0
        holder.itemView.setPaddingRelative(startPad + indent, 0, endPad, 0)
        (holder.name.layoutParams as ViewGroup.MarginLayoutParams).marginStart = (10 * density).toInt()
        holder.add.visibility = View.GONE
        holder.add.setOnClickListener(null)
        holder.addLabel.visibility = View.GONE
        holder.addLabel.setOnClickListener(null)
        holder.icon.visibility = View.VISIBLE
        holder.handle.visibility = View.VISIBLE
        holder.name.alpha = 1f
        holder.name.setTypeface(null, Typeface.NORMAL)
        holder.name.textSize = 16f
        holder.name.text = row.name
        holder.icon.setImageDrawable(row.drawable)
        holder.handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
        holder.itemView.isLongClickable = true
        holder.itemView.setOnLongClickListener {
            onItemMenu(row, holder.itemView)
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val content: View = v.findViewById(R.id.rowContent)
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val handle: ImageView = v.findViewById(R.id.ivHandle)
        val add: ImageView = v.findViewById(R.id.ivAdd)
        val addLabel: TextView = v.findViewById(R.id.tvAddLabel)
        val divider: View = v.findViewById(R.id.rowDivider)
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
