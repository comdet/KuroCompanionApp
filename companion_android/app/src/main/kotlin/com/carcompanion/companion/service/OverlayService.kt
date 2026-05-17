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
 *
 * Position is persisted in SharedPreferences and clamped to the visible
 * screen area on both load and drag — without the clamp the user could
 * push the overlay behind the system bar / nav bar and lose the ability
 * to tap it. [ACTION_RESET_POSITION] gives a "recovery" path back to the
 * default coordinates from outside the service.
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESET_POSITION) {
            resetPosition()
        }
        return START_NOT_STICKY
    }

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
            // FLAG_LAYOUT_IN_SCREEN asks the WindowManager to lay us out
            // against the full display rect (ignoring decor like the
            // status bar). Combined with FLAG_LAYOUT_NO_LIMITS, the
            // overlay can be positioned at y=0 (display top edge).
            // Note: the status bar still renders ON TOP of any pixels
            // beneath it for third-party APPLICATION_OVERLAY — that's a
            // Google-imposed security limit that no flag can lift. The
            // upper strip of the overlay will visually appear under the
            // status bar; the rest stays touchable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Load saved position then **clamp to a safe band** below the
            // status bar. Earlier builds let the overlay drift behind the
            // system bar where it can't be tapped — those saved
            // coordinates won't survive reload.
            val (sx, sy) = clampToSafeBand(
                prefs.getInt(KEY_X, DEFAULT_X),
                prefs.getInt(KEY_Y, DEFAULT_Y),
            )
            x = sx
            y = sy
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
        // Persist the clamped value so a "stuck behind statusbar" position
        // never sticks across restarts.
        savePositionIfChanged()
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
        val (nx, ny) = clampToSafeBand(
            lp.x + dx.toInt(),
            lp.y + dy.toInt(),
        )
        lp.x = nx
        lp.y = ny
        runCatching { windowManager.updateViewLayout(v, lp) }
        savePositionIfChanged()
    }

    /**
     * Reset to defaults — used as a "recovery" path when the overlay
     * has drifted off-screen or behind a system bar. Triggered via
     * [ACTION_RESET_POSITION] from [OverlayController.resetPosition].
     */
    private fun resetPosition() {
        val lp = params ?: return
        val v = view ?: return
        val (nx, ny) = clampToSafeBand(DEFAULT_X, DEFAULT_Y)
        lp.x = nx
        lp.y = ny
        runCatching { windowManager.updateViewLayout(v, lp) }
        savePositionIfChanged()
    }

    /**
     * Clamp [x] / [y] so the overlay can be dragged to (or past) any edge
     * of the display, while always leaving a small grab strip on-screen.
     *
     * Three iterations to get here:
     *
     *   1. **No clamp** — overlay drifted off-screen, no way back.
     *   2. **Insets-based safe band** — used `WindowInsets.systemBars()`
     *      to forbid the status-bar / nav-bar regions. On the FYT/SYU HUD
     *      the reported `insets.top` was big enough that every upward drag
     *      bounced back; overlay felt frozen.
     *   3. **Display-bounds, fully on-screen** (`coerceIn(0, max)`).
     *      Allowed flush-against-edge but the user couldn't push the
     *      overlay over the top edge to overlap the status bar.
     *
     * Current rule: allow the overlay to go past any edge as long as at
     * least [MIN_VISIBLE_DP] of it stays inside the display rect — so a
     * grab handle is always reachable. The status bar still renders on
     * top of the upper strip (Android third-party security limit), but
     * the touchable bottom edge is enough to drag it back down. The
     * "Reset position" button in HomeScreen is the panic-recovery path.
     */
    private fun clampToSafeBand(x: Int, y: Int): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val approxW = (DEFAULT_W_DP * density).toInt()
        val approxH = (DEFAULT_H_DP * density).toInt()
        val viewW = view?.width?.takeIf { it > 0 } ?: approxW
        val viewH = view?.height?.takeIf { it > 0 } ?: approxH
        val minVisible = (MIN_VISIBLE_DP * density).toInt()

        // currentWindowMetrics.bounds INCLUDES the system bars (insets
        // aren't subtracted — verified against Android docs). So with
        // minY = -(viewH - minVisible) the overlay can be pushed up
        // until only a `minVisible`-tall strip remains below the top
        // edge of the display.
        val bounds = windowManager.currentWindowMetrics.bounds
        val minX = -(viewW - minVisible)
        val minY = -(viewH - minVisible)
        val maxX = bounds.width()  - minVisible
        val maxY = bounds.height() - minVisible
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /**
     * Persist the current overlay position. SharedPreferences.apply()
     * is async + coalescing so calling this on every drag step is fine —
     * we get crash-safety without thrash. Skips the write if nothing
     * actually changed.
     */
    private fun savePositionIfChanged() {
        val lp = params ?: return
        if (lp.x == lastSavedX && lp.y == lastSavedY) return
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        lastSavedX = lp.x
        lastSavedY = lp.y
    }

    private fun teardownOverlay() {
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
        const val ACTION_RESET_POSITION = "com.carcompanion.companion.RESET_OVERLAY_POSITION"
        private const val POSITION_PREFS = "overlay_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val DEFAULT_X = 80
        private const val DEFAULT_Y = 200
        // Rough size used for clamping before the view has been measured.
        private const val DEFAULT_W_DP = 300f
        private const val DEFAULT_H_DP = 60f
        // Minimum strip of overlay that must remain on-screen at every
        // edge, so the user can always grab it back even after pushing
        // it past the top / bottom / sides. 24 dp is the Material touch
        // target floor.
        private const val MIN_VISIBLE_DP = 24f
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

    /**
     * Recovery: move the floating overlay back to its default coords.
     * Works whether or not the service is currently running — if it
     * isn't, the saved-position wipe alone is enough for the next
     * [show] to land at defaults.
     */
    fun resetPosition(context: Context) {
        // Wipe persisted prefs so a not-running service comes back home.
        context.getSharedPreferences("overlay_position", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        // Poke the running service (if any) to move now.
        val intent = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_RESET_POSITION)
        runCatching { context.startService(intent) }
    }
}
