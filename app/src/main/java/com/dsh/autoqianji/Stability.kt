package com.dsh.autoqianji

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * 国产 ROM 的稳定性设置跳转。
 * 这些开关都在系统侧，App 只能把用户带到对应页面并显示当前状态。
 */
object Stability {

    /** 是否已忽略电池优化（true 表示不会因为省电被限制） */
    fun isIgnoringBattery(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 自启动：直接打开本应用的应用信息页。
     * 和钱迹一致——MIUI 在这个页面底部就有「自启动」开关，比跳自启动列表再找一次省事。
     */
    fun openAutostart(ctx: Context) {
        openAppDetail(ctx)
    }

    /** 忽略电池优化 */
    fun requestIgnoreBattery(ctx: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + ctx.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return
        } catch (_: Exception) {
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "当前系统没有这个设置页", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从最近任务里隐藏。
     * 用公开 API ActivityManager.AppTask.setExcludeFromRecents（API 21+），
     * 反射 Activity.setExcludeFromRecents 在新系统上已经被移除，不能再用。
     */
    fun applyHideFromRecents(activity: Activity, hide: Boolean): Boolean {
        return try {
            val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return false
            val tasks = am.appTasks
            if (tasks.isNullOrEmpty()) {
                android.util.Log.w("AutoQianji", "appTasks empty, hide=$hide")
                return false
            }
            tasks.forEach { it.setExcludeFromRecents(hide) }
            android.util.Log.i("AutoQianji", "AppTask.setExcludeFromRecents($hide) on ${tasks.size} task(s)")
            true
        } catch (t: Throwable) {
            android.util.Log.w("AutoQianji", "setExcludeFromRecents failed: $t")
            false
        }
    }

    /** 支付保护：小米会拦截跳转，只能提示用户去系统里放行 */

    fun openAppDetail(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    fun isMiui(): Boolean = Build.MANUFACTURER.equals("Xiaomi", true) ||
        Build.BRAND.equals("Xiaomi", true) ||
        Build.BRAND.equals("Redmi", true) ||
        Build.BRAND.equals("POCO", true)
}
