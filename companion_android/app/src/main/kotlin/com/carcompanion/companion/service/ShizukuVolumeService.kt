package com.carcompanion.companion.service

import android.os.IBinder
import android.util.Log

/**
 * Shizuku user-service implementation.
 *
 * Lives inside Shizuku's process (UID 2000 — shell) once
 * [rikka.shizuku.Shizuku.bindUserService] connects us. From here we have
 * the same privileges as `adb shell`, so spawning `/system/bin/input` and
 * letting it inject a `KEYCODE_VOLUME_UP/DOWN/MUTE` reaches the system
 * InputDispatcher — which the SYU / FYT / XYAuto HUD ROM hooks to drive
 * the external amp module.
 *
 * Construction note: Shizuku's user-service infrastructure instantiates
 * us reflectively, requiring **either** a no-arg constructor **or** one
 * that takes a single [Context]. We use the [Context] variant so future
 * extensions (e.g. reading SystemProperties) have it available.
 */
class ShizukuVolumeService() : IShizukuVolumeService.Stub() {

    // Shizuku may instantiate us with a Context arg via reflection on
    // older Manager versions. Accept either; we don't actually need it
    // for the current `input keyevent` flow.
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context) : this()

    override fun sendKey(keycode: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/input", "keyevent", keycode)
            )
            proc.waitFor()
        } catch (e: Throwable) {
            Log.w(TAG, "sendKey($keycode) failed: ${e.message}")
            -1
        }
    }

    override fun destroy() {
        // Nothing held — Shizuku will tear down the process when refcount
        // drops to zero.
    }

    override fun asBinder(): IBinder = this

    companion object {
        private const val TAG = "ShizukuVolSvc"
    }
}
