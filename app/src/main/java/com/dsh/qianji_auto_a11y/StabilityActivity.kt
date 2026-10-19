package com.dsh.qianji_auto_a11y

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 二级页：国产系统的后台管控开关 */
class StabilityActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stability)
        StickyHeader.attach(this)

        val rows = findViewById<LinearLayout>(R.id.rowsOptimize)
        rows.removeAllViews()

        navRow(rows, R.drawable.ic_line_power, "自启动", "减少后台被清理，需在系统里开启") {
            Stability.openAutostart(this)
        }
        navRow(
            rows, R.drawable.ic_line_battery, "忽略电池优化",
            if (Stability.isIgnoringBattery(this)) "已忽略电池优化" else "避免省电策略限制后台"
        ) {
            Stability.requestIgnoreBattery(this)
        }
        switchRow(
            rows, R.drawable.ic_line_eye_off, "从最近任务中隐藏",
            sub = "最近任务里不显示本应用",
            get = { Config.hideFromRecents(this) },
            onChange = {
                Config.setHideFromRecents(this, it)
                Stability.applyHideFromRecents(this, it)
            }
        )
        navRow(rows, R.drawable.ic_line_shield, "支付保护", "浮窗切回桌面才出现？看解决办法") {
            AlertDialog.Builder(this)
                .setTitle("支付保护")
                .setMessage(
                    "如果自动记账弹窗不能在支付页面弹出，而是切换回桌面或者钱迹才显示，" +
                        "这是因为一些系统针对支付类 App 做了保护，避免在这些支付 App 上层显示其他应用。\n\n" +
                        "解决方案：\n" +
                        "1. 打开手机系统的设置，搜索【支付安全检测】或者【支付保护】；\n" +
                        "2. 将微信、支付宝、云闪付 关闭即可。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun navRow(container: LinearLayout, icon: Int, title: String, sub: String, onClick: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_setting, container, false)
        v.findViewById<ImageView>(R.id.ivSettingIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tvSettingTitle).text = title
        val subView = v.findViewById<TextView>(R.id.tvSettingSub)
        subView.visibility = View.VISIBLE
        subView.text = sub
        v.findViewById<ImageView>(R.id.ivSettingArrow)?.visibility = View.GONE
        v.setOnClickListener { onClick() }
        container.addView(v)
    }

    private fun switchRow(
        container: LinearLayout,
        icon: Int,
        title: String,
        sub: String,
        get: () -> Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val v = layoutInflater.inflate(R.layout.item_setting, container, false)
        v.findViewById<ImageView>(R.id.ivSettingIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.tvSettingTitle).text = title
        val subView = v.findViewById<TextView>(R.id.tvSettingSub)
        subView.visibility = View.VISIBLE
        subView.text = sub

        val arrow = v.findViewById<ImageView>(R.id.ivSettingArrow)
        (arrow.parent as android.view.ViewGroup).removeView(arrow)

        val sw = SwitchCompat(this).apply { isChecked = get() }
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        (v as android.view.ViewGroup).addView(sw)
        v.setOnClickListener { sw.toggle() }
        container.addView(v)
    }
}
