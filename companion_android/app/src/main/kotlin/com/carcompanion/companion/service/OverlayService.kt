package com.carcompanion.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.carcompanion.companion.ui.overlay.OverlayContentLive

/**
 * Floats a draggable Compose card over other apps using SYSTEM_ALERT_WINDOW.
 *
 * Bring up: `OverlayController.show(context)` (after permission granted).
 * Take down: `OverlayController.hide(context)` or service stops naturally.
 *
 * NOTE: this is a regular [Service] (not LifecycleService). The Compose tree
 * gets its own minimal lifecycle via [OverlayLifecycleOwner].
 */
class OverlayService : Service() {

    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private val windowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    /** Persisted position cache. SharedPreferences is fine here — two ints. */
    private val prefs by lazy {
        getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
    }
    /** Last position we wrote to disk; used to skip redundant `apply()`. */
    private var lastSavedX = Int.MIN_VALUE
    private var lastSavedY = Int.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardownOverlay()
        super.onDestroy()
    }

    private fun createOverlay() {
        val lifecycleOwner = OverlayLifecycleOwner().also { owner = it }
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore last-saved position, fall back to a sensible default
            // the first time the overlay is shown. The defaults (80, 200)
            // sit in the upper-left away from the system status bar.
            x = prefs.getInt(KEY_X, 80)
            y = prefs.getInt(KEY_Y, 200)
            lastSavedX = x
            lastSavedY = y
        }
        params = layoutParams

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                OverlayContentLive(
                    onVolumeDown = { CarCompanionService.bumpVolume(-1) },
                    onVolumeUp = { CarCompanionService.bumpVolume(+1) },
                    onMuteToggle = { CarCompanionService.toggleMute() },
                    onDebug = { openMainActivity() },
                    onClose = { OverlayController.hide(this@OverlayService) },
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                )
            }
        }
        view = composeView

        windowManager.addView(composeView, layoutParams)
    }

    /**
     * Open the main activity in manual mode so it doesn't immediately
     * re-launch the overlay and finish() itself. Lets the user reach
     * Soul/Sensors/Cfg without losing the floating UI.
     */
    private fun openMainActivity() {
        val cls = com.carcompanion.companion.ui.main.MainActivity::class.java
        val intent = Intent(this, cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.carcompanion.companion.ui.main.MainActivity.EXTRA_MANUAL, true)
        startActivity(intent)
    }

    /** Shift window position by pixel delta; called from Compose drag handle. */
    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val v = view ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager.updateViewLayout(v, lp) }
        // Persist on every drag-step too. SharedPreferences.apply() is
        // async + coalescing, so the ~50 calls a real drag generates
        // collapse into a single disk write — but we get crash-safety
        // (service killed mid-drag still preserves the last position).
        savePositionIfChanged()
    }

    /**
     * Persist the current overlay position. Called from teardown so we
     * don't hammer SharedPreferences on every drag delta (a single drag
     * can fire 50+ events). One write per session is plenty since the
     * service is the only writer.
     */
    private fun savePositionIfChanged() {
        val lp = params ?: return
        if (lp.x == lastSavedX && lp.y == lastSavedY) return
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        lastSavedX = lp.x
        lastSavedY = lp.y
    }

    private fun teardownOverlay() {
        // Save position before clearing params — moveBy() may have shifted
        // it during this session.
        savePositionIfChanged()
        val v = view
        view = null
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        owner = null
        if (v != null) {
            runCatching { windowManager.removeView(v) }
        }
        params = null
    }

    companion object {
        private const val POSITION_PREFS = "overlay_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

object OverlayController {
    fun isPermissionGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (!isPermissionGranted(context)) return
        context.startService(Intent(context, OverlayService::class.java))
    }

    fun hide(context: Context) {
        context.stopService(Intent(context, OverlayService::class.java))
    }
}
