package moe.ddlauncher

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 游戏内悬浮退出钮。
 *
 * 这个移植版的游戏里没有任何退出入口，只能从最近任务里划掉（大退）。启动器在
 * `launchGame()` 时起一个前台服务，在游戏画面上叠一个小圆钮：
 * 拖动可以挪位置，点一下变「确认?」，2.5 秒内再点一下就 kill 掉 `:nx` 进程。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），没授权就不显示。
 */
object ExitOverlay {

    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun start(ctx: Context) {
        if (!canDraw(ctx)) return
        runCatching {
            val intent = Intent(ctx, ExitOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, ExitOverlayService::class.java)) }
    }
}

class ExitOverlayService : Service() {

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var armed = false

    private val disarm = Runnable {
        armed = false
        bubble?.text = "退出"
    }

    private val watch = object : Runnable {
        override fun run() {
            if (gamePids().isEmpty()) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        if (bubble == null) showBubble()
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, 2000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bubble?.let { v -> runCatching { wm?.removeView(v) } }
        bubble = null
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = "game_exit"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channel) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channel, "游戏运行中", NotificationManager.IMPORTANCE_MIN),
                )
            }
            val notif = Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle("Darkest Dungeon 运行中")
                .setContentText("游戏内右上角有「退出」钮")
                .setOngoing(true)
                .build()
            startForeground(0x6e78, notif)
        }
    }

    private fun showBubble() {
        val ctx = this
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val size = (52 * resources.displayMetrics.density).toInt()
        val view = TextView(ctx).apply {
            text = "退出"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 24, 24, 24))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(150, 220, 120, 60))
            }
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (96 * resources.displayMetrics.density).toInt()
        }
        params = lp

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    if (moved) {
                        // x 是从右边算的，往右拖要减小
                        lp.x = (startX - dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { wm?.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) tap()
                    true
                }
                else -> true
            }
        }

        runCatching { manager.addView(view, lp) }
        bubble = view
    }

    private fun tap() {
        if (!armed) {
            armed = true
            bubble?.text = "确认?"
            handler.removeCallbacks(disarm)
            handler.postDelayed(disarm, 2500L)
            return
        }
        handler.removeCallbacks(disarm)
        doExit()
    }

    /**
     * 退出前先给存档打一份快照（存档目录只有几十 KB，很快），再杀进程。
     * 游戏本身是边玩边 commit save 的，正常不会丢进度；快照只是防「正好写在
     * 一半被杀」这种极端情况。
     */
    private fun doExit() {
        // 先把钮收掉，手感上是「点一下就退了」
        bubble?.let { v -> runCatching { wm?.removeView(v) } }
        bubble = null

        val pids = gamePids()
        DdLog.i(this, "game", "exit overlay: kill :nx pids=$pids")
        pids.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }

        // 杀完再补一份快照（服务还活着，进程不会被回收）
        Thread {
            runCatching { SaveRepo.create("before-exit") }
                .onSuccess { DdLog.i(this, "save", "snapshot before exit: ${it.file.name}") }
                .onFailure { DdLog.e(this, "save", it) }
            handler.post { stopSelf() }
        }.start()
    }

    private fun gamePids(): List<Int> {
        val am = getSystemService(ActivityManager::class.java) ?: return emptyList()
        val name = "$packageName:nx"
        return am.runningAppProcesses.orEmpty()
            .filter { it.processName == name && it.pid > 0 && it.pid != android.os.Process.myPid() }
            .map { it.pid }
    }
}
