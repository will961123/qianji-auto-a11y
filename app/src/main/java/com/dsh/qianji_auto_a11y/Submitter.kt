package com.dsh.qianji_auto_a11y

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 钱迹的 addbill 接口有 3 秒频率限制，所以这里做成串行队列：
 * 先落一条 QUEUED 记录，再按顺序一笔一笔发，发完把状态改成 SENT / FAIL。
 */
object Submitter {

    private const val QIANJI_PKG = "com.mutangtech.qianji"
    private const val MIN_INTERVAL_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private var overlay: View? = null

    private val queue = ArrayDeque<Task>()
    private var busy = false
    private var lastAt = 0L

    private data class Task(val id: Long, val draft: Draft)

    /** 入队；调试模式下只落日志不发送 */
    fun submit(ctx: Context, d: Draft) {
        val app = ctx.applicationContext
        val store = Store(app)
        if (Config.dryRun(app)) {
            store.addLog(d, "DRYRUN", "未提交（调试模式）")
            return
        }
        val id = store.addLog(d, "QUEUED", "排队中")
        synchronized(this) { queue.addLast(Task(id, d)) }
        pump(app)
    }

    /** 重新发一笔已有的记录：不走队列、不判重，直接发送并回写状态 */
    fun resendNow(ctx: Context, d: Draft, id: Long): Boolean =
        send(ctx, buildUri(d), Store(ctx), d, id)

    /** 立即发一笔（测试账单用），不走队列 */
    fun submitNow(ctx: Context, d: Draft): Boolean =
        send(ctx, buildUri(d), Store(ctx), d, id = null)

    private fun pump(ctx: Context) {
        val task = synchronized(this) {
            if (busy || queue.isEmpty()) return
            busy = true
            queue.removeFirst()
        }
        val wait = (lastAt + MIN_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed({
            lastAt = System.currentTimeMillis()
            send(ctx, buildUri(task.draft), Store(ctx), task.draft, task.id)
            synchronized(this) { busy = false }
            pump(ctx)
        }, wait)
    }

    private fun buildUri(d: Draft): Uri {
        val b = Uri.parse("qianji://publicapi/addbill").buildUpon()
            .appendQueryParameter("type", d.type.toString())
            .appendQueryParameter("money", String.format(Locale.US, "%.2f", d.amount))
            .appendQueryParameter("time", d.time ?: timeFmt.format(Date()))
            .appendQueryParameter("remark", d.remark)
            .appendQueryParameter("showresult", "0")
        if (d.cateChoose) {
            // catechoose 与 catename 互斥：带上它，钱迹会弹出分类面板
            b.appendQueryParameter("catechoose", "1")
            b.appendQueryParameter("catetheme", "auto")
        } else if (d.catename.isNotBlank()) {
            b.appendQueryParameter("catename", d.catename)
        }
        if (d.account.isNotBlank()) b.appendQueryParameter("accountname", d.account)
        if (d.account2.isNotBlank()) b.appendQueryParameter("accountname2", d.account2)
        if (d.fee.isNotBlank()) b.appendQueryParameter("fee", d.fee)
        if (d.coupon.isNotBlank()) b.appendQueryParameter("coupon", d.coupon)
        if (d.book.isNotBlank()) b.appendQueryParameter("bookname", d.book)
        return b.build()
    }

    /** 挂一个 1px 悬浮窗：有了可见窗口 + 悬浮窗权限，后台才能启动钱迹的界面 */
    private fun ensureOverlay(ctx: Context) {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w("AutoQianji", "no overlay permission, background start may be blocked")
            return
        }
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.TOP
            wm.addView(v, lp)
            overlay = v
            Log.d("AutoQianji", "overlay attached")
        } catch (e: Exception) {
            Log.w("AutoQianji", "overlay fail: " + e.message)
        }
    }

    private fun send(ctx: Context, uri: Uri, store: Store, d: Draft, id: Long?): Boolean {
        ensureOverlay(ctx)
        // 后台又没悬浮窗权限时，系统会静默拦掉这次启动，别假装发成功了
        if (id != null && !isForeground(ctx) && !Settings.canDrawOverlays(ctx)) {
            store.updateLogStatus(id, "FAIL", "缺少「后台弹出界面 / 悬浮窗」权限，系统拦掉了跳转")
            return false
        }
        val pinned = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(QIANJI_PKG)
        }
        try {
            ctx.startActivity(pinned)
            markSent(store, d, id, uri.toString())
            return true
        } catch (first: Exception) {
            val any = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ctx.startActivity(any)
                markSent(store, d, id, "fallback: " + uri.toString())
                true
            } catch (second: Exception) {
                val detail = "${first.javaClass.simpleName}/${second.message}"
                if (id != null) store.updateLogStatus(id, "FAIL", detail)
                else store.addLog(d, "FAIL", detail)
                false
            }
        }
    }

    private fun isForeground(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val importance = am.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.importance
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (e: Exception) {
            false
        }
    }

    private fun markSent(store: Store, d: Draft, id: Long?, detail: String) {
        if (id != null) store.updateLogStatus(id, "SENT", detail)
        else store.addLog(d, "SENT", detail)
    }
}
