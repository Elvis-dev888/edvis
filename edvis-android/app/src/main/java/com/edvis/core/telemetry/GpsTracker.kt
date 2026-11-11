package com.edvis.core.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Continuous high-accuracy GPS telemetry engine for dynamic route bypass and speed awareness.
 */
class GpsTracker(
    context: Context,
    private val onTelemetryUpdate: (lat: Double, lng: Double, speedKmh: Float, bearing: Float) -> Unit
) {
    companion object {
        private const val TAG = "GpsTracker"
        private const val INTERVAL_MS = 5000L
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val speedKmh = loc.speed * 3.6f
            onTelemetryUpdate(loc.latitude, loc.longitude, speedKmh, loc.bearing)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(2500L)
                .build()

            fusedClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "High-accuracy GPS telemetry started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS tracking: ${e.message}")
        }
    }

    fun stopTracking() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GPS tracking: ${e.message}")
        }
    }
}
