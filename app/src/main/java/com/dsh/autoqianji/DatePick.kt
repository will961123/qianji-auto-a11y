package com.dsh.autoqianji

import androidx.fragment.app.FragmentActivity
import com.google.android.material.datepicker.MaterialDatePicker
import java.util.Calendar
import java.util.TimeZone

/**
 * 系统自带的 DatePickerDialog 太素，统一换成 Material 的日期选择器。
 * 它自带深色/浅色主题、月份切换动画和「今天 / 确定」按钮。
 */
object DatePick {

    fun show(activity: FragmentActivity, current: Calendar, onPick: (Calendar) -> Unit) {
        // MaterialDatePicker 用 UTC 毫秒，这里做一次本地时区换算
        val localMillis = current.timeInMillis
        val selection = localMillis - TimeZone.getDefault().getOffset(localMillis)

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择日期")
            .setSelection(selection)
            .build()

        picker.addOnPositiveButtonClickListener { utc ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = utc + TimeZone.getDefault().getOffset(utc)
            onPick(cal)
        }
        picker.show(activity.supportFragmentManager, "date_picker")
    }
}
