package com.dsh.autoqianji

import android.content.Context

object Config {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun dryRun(c: Context) = sp(c).getBoolean("dry", false)
    fun setDryRun(c: Context, v: Boolean) = sp(c).edit().putBoolean("dry", v).apply()

    fun book(c: Context) = sp(c).getString("book", "") ?: ""
    fun setBook(c: Context, v: String) = sp(c).edit().putString("book", v).apply()

    fun account(c: Context, pkg: String) = sp(c).getString("acc_$pkg", "") ?: ""
    fun setAccount(c: Context, pkg: String, v: String) =
        sp(c).edit().putString("acc_$pkg", v).apply()

    /** 页面读不到付款方式时的兜底资产 */
    fun defaultAccount(c: Context) = sp(c).getString("default_account", "") ?: ""
    fun setDefaultAccount(c: Context, v: String) =
        sp(c).edit().putString("default_account", v).apply()

    fun lastImportAt(c: Context) = sp(c).getLong("import_at", 0L)
    fun setLastImportAt(c: Context, v: Long) = sp(c).edit().putLong("import_at", v).apply()

    /** 识别到账单后先弹悬浮窗确认，再提交 */
    fun confirmBeforeSubmit(c: Context) = sp(c).getBoolean("confirm_before_submit", true)
    fun setConfirmBeforeSubmit(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("confirm_before_submit", v).apply()

    /** 设置页滚动位置 */
    fun settingsScroll(c: Context) = sp(c).getInt("settings_scroll", 0)
    fun setSettingsScroll(c: Context, v: Int) = sp(c).edit().putInt("settings_scroll", v).apply()

    /** 上次停留的页签 */
    fun lastTab(c: Context) = sp(c).getString("last_tab", "home") ?: "home"
    fun setLastTab(c: Context, v: String) = sp(c).edit().putString("last_tab", v).apply()

    /** 从最近任务里隐藏 */
    fun hideFromRecents(c: Context) = sp(c).getBoolean("hide_recents", true)
    fun setHideFromRecents(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("hide_recents", v).apply()

    /** 所有规则都没命中时用的兜底分类，空表示记到「其它」 */
    fun defaultCategory(c: Context) = sp(c).getString("default_category", "") ?: ""
    fun setDefaultCategory(c: Context, v: String) =
        sp(c).edit().putString("default_category", v).apply()
}
