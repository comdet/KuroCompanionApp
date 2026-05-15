package com.carcompanion.companion.data.repo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coarse-location source for the weather analyzer.
 *
 * Uses [LocationManager] (not FusedLocationProviderClient) on purpose: the
 * FYT7870 HUD target ships without Google Play Services, and a head unit
 * without GMS would crash at runtime trying to bind to it. Vanilla
 * LocationManager works on every Android device, including AOSP cars.
 *
 * Provider priority:
 *   1. NETWORK_PROVIDER — WiFi/cell triangulation, low power, city-block
 *      accuracy. Plenty for "is it raining where I am".
 *   2. GPS_PROVIDER — fallback if network is the only enabled provider
 *      and we still got nothing after the last-known check.
 *
 * Permission expectations:
 *   - Manifest declares ACCESS_COARSE_LOCATION (see AndroidManifest.xml).
 *   - Runtime grant is requested from MainActivity. If denied, [start]
 *     returns false and [current] stays null — the service falls back to
 *     the Bangkok default.
 */
class LocationProvider(private val context: Context) {

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _current = MutableStateFlow<LatLon?>(null)
    val current: StateFlow<LatLon?> = _current.asStateFlow()

    private var listener: LocationListener? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Begin tracking. Pulls last-known immediately if available, then
     * subscribes for incremental updates. Idempotent — calling twice is a
     * no-op.
     *
     * @return true if at least one provider was subscribed.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listener != null) return true
        val mgr = lm ?: return false
        if (!hasPermission()) {
            Log.w(TAG, "ACCESS_COARSE_LOCATION not granted; staying on fallback")
            return false
        }

        // Last-known is instant — usually whatever WiFi told the system minutes
        // ago. Good enough for the first weather poll.
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { p ->
            runCatching {
                if (mgr.isProviderEnabled(p)) mgr.getLastKnownLocation(p)?.let(::onLocation)
            }
        }

        val cb = LocationListener { loc -> onLocation(loc) }
        var subscribed = false
        runCatching {
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS, UPDATE_MIN_METERS,
                    cb, Looper.getMainLooper(),
                )
                subscribed = true
            }
        }.onFailure { Log.w(TAG, "NETWORK provider failed: ${it.message}") }

        if (!subscribed) {
            // Fall back to GPS if network provider isn't enabled (rare on
            // phones, common on car head units).
            runCatching {
                if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    mgr.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS, UPDATE_MIN_METERS,
                        cb, Looper.getMainLooper(),
                    )
                    subscribed = true
                }
            }.onFailure { Log.w(TAG, "GPS provider failed: ${it.message}") }
        }

        if (subscribed) listener = cb
        return subscribed
    }

    fun stop() {
        val cb = listener ?: return
        lm?.removeUpdates(cb)
        listener = null
    }

    private fun onLocation(loc: Location) {
        val ll = LatLon(loc.latitude, loc.longitude)
        _current.value = ll
        Log.d(TAG, "location update: %.4f, %.4f (provider=${loc.provider})".format(ll.lat, ll.lon))
    }

    companion object {
        private const val TAG = "LocationProvider"
        private const val UPDATE_INTERVAL_MS = 10L * 60_000L   // 10 min — matches weather poll
        private const val UPDATE_MIN_METERS = 1_000f           // 1 km — city granularity is enough
    }
}

data class LatLon(val lat: Double, val lon: Double)
