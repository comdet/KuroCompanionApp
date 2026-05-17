package com.carcompanion.companion.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Volume control over Shizuku.
 *
 * Background: on Chinese head-unit ROMs (SYU / XYAuto / FYT class) the real
 * amp lives outside Android. The ROM hooks `KEYCODE_VOLUME_UP/DOWN/MUTE` at
 * the **InputDispatcher** level and forwards them to the amp module. A
 * third-party app cannot inject input events at that level directly
 * (`INJECT_EVENTS` is signature-only), and the public
 * [android.media.AudioManager.dispatchMediaKeyEvent] goes through
 * MediaSessionService — a different path that the ROM does not hook.
 *
 * Shizuku's `Shizuku.newProcess` API is annotated `private` in v13+, so
 * the supported path is a **user service**: we declare an AIDL interface
 * ([IShizukuVolumeService]), Shizuku spawns
 * [ShizukuVolumeService] inside its own UID-2000 (shell) process, and we
 * call methods on it through a normal Android binder. Inside that process
 * `/system/bin/input keyevent KEYCODE_VOLUME_UP` reaches the same path as
 * `adb shell input keyevent` — i.e. the path the ROM **does** hook.
 *
 * Usage:
 *   - User installs Shizuku and activates it once (Android 11+ supports
 *     activation through the device's own wireless-debug pairing).
 *   - Our app calls [warmUp] from service start so the binder is hot by
 *     the time the user taps a volume button. (Bind is async.)
 *   - [sendKey] returns true if the user-service round-trip succeeded.
 *     If anything in the chain isn't ready it returns false and the
 *     caller falls back to [android.media.AudioManager.dispatchMediaKeyEvent].
 */
object ShizukuVolumeController {

    private const val TAG = "ShizukuVolumeCtl"
    /** Bump if the AIDL surface changes — Shizuku tears down old user-services. */
    private const val USER_SERVICE_VERSION = 1

    @Volatile private var userService: IShizukuVolumeService? = null
    @Volatile private var binding: Boolean = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "user service connected but binder dead")
                userService = null
                return
            }
            userService = IShizukuVolumeService.Stub.asInterface(binder)
            Log.i(TAG, "user service ready")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binding = false
            userService = null
            Log.w(TAG, "user service disconnected")
        }
    }

    /** Shizuku binder reachable AND service is alive. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        Log.d(TAG, "ping failed: ${e.message}")
        false
    }

    /**
     * True when the user has tapped "Allow" on Shizuku's permission popup
     * for this app at some point. Shizuku persists this across service
     * restarts.
     */
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /**
     * Pop up Shizuku's permission dialog. Has to be called while the app
     * is foreground (otherwise the dialog never shows). Result arrives
     * asynchronously through [Shizuku.addRequestPermissionResultListener].
     */
    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "requestPermission failed: ${it.message}") }
    }

    /**
     * Start binding the user service if everything is ready. Safe to call
     * repeatedly — already-bound + in-flight binds are no-ops. Bind is
     * async; the first [sendKey] right after [warmUp] may still race and
     * fall through to the AudioManager path.
     */
    fun warmUp(context: Context) {
        if (userService != null || binding) return
        if (!isAvailable() || !hasPermission()) return
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuVolumeService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_vol")
            .debuggable(false)
            .version(USER_SERVICE_VERSION)
        try {
            binding = true
            Shizuku.bindUserService(args, serviceConn)
        } catch (e: Throwable) {
            binding = false
            Log.w(TAG, "bindUserService failed: ${e.message}")
        }
    }

    /**
     * Fire-and-forget `input keyevent <keycode>` through the user-service
     * binder. Returns true on a clean exit; false if the user service
     * isn't bound yet, the binder died, or `input` itself exited non-zero.
     *
     * @param keycode symbolic name `input` accepts (e.g. "KEYCODE_VOLUME_UP").
     */
    fun sendKey(keycode: String): Boolean {
        val svc = userService ?: return false
        return try {
            val rc = svc.sendKey(keycode)
            if (rc != 0) Log.w(TAG, "sendKey($keycode) rc=$rc")
            rc == 0
        } catch (e: Throwable) {
            // Binder died mid-call — drop the reference so the next warmUp rebinds.
            Log.w(TAG, "sendKey($keycode) binder fault: ${e.message}")
            userService = null
            false
        }
    }

    /** Status snapshot for debug UI. */
    fun status(): Status = when {
        !isAvailable() -> Status.NOT_INSTALLED
        !hasPermission() -> Status.NO_PERMISSION
        userService == null -> Status.BINDING
        else -> Status.READY
    }

    enum class Status { NOT_INSTALLED, NO_PERMISSION, BINDING, READY }
}
