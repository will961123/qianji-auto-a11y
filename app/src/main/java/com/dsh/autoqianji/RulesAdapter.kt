package com.dsh.autoqianji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 规则列表：点=编辑，长按=删除，右侧把手拖动排序 */
class RulesAdapter(
    private val items: MutableList<RuleRow>,
    private val display: (String) -> String,
    private val onClick: (RuleRow) -> Unit,
    private val onLongClick: (RuleRow) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RulesAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val keyword: TextView = view.findViewById(R.id.tvKeyword)
        val cate: TextView = view.findViewById(R.id.tvCate)
        val drag: ImageView = view.findViewById(R.id.ivDrag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.keyword.text = row.keyword
        holder.cate.text = display(row.catename)
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener {
            onLongClick(row)
            true
        }
        holder.drag.setOnTouchListener { _, _ ->
            onStartDrag(holder)
            false
        }
    }
}
