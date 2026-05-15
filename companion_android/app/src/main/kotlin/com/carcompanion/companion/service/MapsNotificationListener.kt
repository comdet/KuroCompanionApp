package com.carcompanion.companion.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Captures live notifications from navigation apps (Google Maps, Waze, …)
 * so the soul engine can react to "ใกล้ถึงแล้ว / อุบัติเหตุข้างหน้า / ติด"
 * announcements even though those apps don't expose a public navigation API.
 *
 * ── How it works ──────────────────────────────────────────────────────────
 * Android grants notification visibility to apps that declare
 * `BIND_NOTIFICATION_LISTENER_SERVICE` AND the user toggles the listener on
 * in Settings → Apps → Special access → Notification access. There is no
 * way to silently grant; the user must enable us per device.
 *
 * The listener fires on **every** notification system-wide, so we filter by
 * package as the very first step. We don't read or store anything from
 * other apps.
 *
 * ── What we extract ───────────────────────────────────────────────────────
 * Each navigation app posts a single ongoing notification that it mutates
 * over time (Maps keeps updating the same id with new turn instructions).
 * We pull:
 *   - android.title   — usually the next maneuver ("In 200 m, turn left")
 *   - android.text    — usually the destination / ETA
 *   - android.bigText — sometimes longer detail
 *   - android.subText — sometimes ETA / distance
 *
 * We send raw text to [CarCompanionService.publishNavNotification]; parsing
 * into SemanticEvents lives there once we've seen actual notification shapes
 * on real devices (log-first approach — no regex until we see real data).
 *
 * ── Verified vs unverified ────────────────────────────────────────────────
 * - Service binding mechanism: standard Android API, well-documented.
 * - Notification field names ("android.title" etc.): standard
 *   NotificationCompat extras keys.
 * - **What Maps/Waze actually put in those fields**: UNVERIFIED. We log raw
 *   to discover the real shape on each device.
 */
class MapsNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected — replaying currently posted notifications")
        // After a rebind we miss the original POSTED callback for active
        // notifications (Maps's nav notification is ongoing). Replay them so
        // the parser sees the current nav state.
        runCatching {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName in TARGET_PACKAGES) capture(sbn, removed = false)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Listener disconnected — requesting rebind")
        // Self-heal: ask the OS to rebind us. Handles the "install replaced
        // app, system never re-binds" case automatically.
        runCatching { requestRebind(ComponentName(this, javaClass)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in TARGET_PACKAGES) return
        capture(n, removed = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in TARGET_PACKAGES) return
        capture(n, removed = true)
    }

    private fun capture(sbn: StatusBarNotification, removed: Boolean) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val captured = NavNotification(
            pkg = sbn.packageName,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            postedAt = sbn.postTime,
            removed = removed,
        )
        Log.d(TAG, "${if (removed) "REMOVED" else "POSTED"} ${sbn.packageName}: " +
            "title='$title' text='$text' big='$bigText' sub='$subText'")
        CarCompanionService.publishNavNotification(captured)
    }

    companion object {
        private const val TAG = "MapsNotifListener"

        /**
         * Packages we observe. Adding here makes us filter their
         * notifications in/out; everything else is dropped at the door.
         */
        val TARGET_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.maps",   // Google Maps
            "com.waze",                       // Waze
        )

        /**
         * Force the OS to (re-)bind our listener service.
         *
         * Background: after an APK install Android won't automatically rebind
         * existing notification listeners — they stay "enabled in Settings
         * but not actually connected". Toggling the component enabled state
         * is the canonical workaround that forces NotificationManagerService
         * to re-evaluate which listeners need binding. `requestRebind()`
         * alone is sometimes ignored on MIUI / vendor-modified ROMs.
         *
         * The component-toggle uses `DONT_KILL_APP` so our foreground service
         * isn't restarted in the process. Safe to call every time the main
         * activity opens — the disable/enable cycle is a few ms and a no-op
         * if the listener is already bound.
         */
        fun requestRebindNow(context: Context) {
            val app = context.applicationContext
            val cn = ComponentName(app, MapsNotificationListener::class.java)
            runCatching {
                val pm = app.packageManager
                pm.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
                pm.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
                Log.d(TAG, "component toggled off/on → listener should rebind")
            }.onFailure { Log.w(TAG, "component toggle failed: ${it.message}") }
            // Belt-and-suspenders: also call the official API.
            runCatching {
                requestRebind(cn)
                Log.d(TAG, "requestRebind() called")
            }.onFailure { Log.w(TAG, "requestRebind failed: ${it.message}") }
        }
    }
}

/**
 * Snapshot of a single notification (or notification-removed event) captured
 * from a navigation app. Fields mirror the [Notification] extras the source
 * app populated; any of the text fields may be empty.
 */
data class NavNotification(
    val pkg: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val postedAt: Long,
    val removed: Boolean = false,
)
