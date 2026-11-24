package com.pintrip.app.travel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pintrip.app.R
import com.pintrip.app.map.RoomMapActivity
import com.pintrip.app.ui.common.LocationPermissionHelper

class TravelModeService : Service() {

    companion object {
        private const val TAG = "TravelModeService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "travel_mode_channel"

        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val ACTION_STOP_SERVICE = "com.pintrip.app.ACTION_STOP_TRAVEL_MODE"

        const val LOCATION_INTERVAL_MS = 30_000L
        const val LOCATION_MIN_DISPLACEMENT_M = 50f
        const val LOCATION_PRIORITY = Priority.PRIORITY_HIGH_ACCURACY
        const val NEARBY_RADIUS_M = 300

        fun startIntent(context: Context, roomId: String, roomName: String): Intent {
            return Intent(context, TravelModeService::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, roomName)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TravelModeService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isLocationRequesting = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            Log.d(TAG, "${location.latitude}, ${location.longitude}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME).orEmpty()

        if (roomId.isBlank()) {
            Log.w(TAG, "roomId is blank, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LocationPermissionHelper.hasLocationPermission(this)) {
            Log.w(TAG, "Location permission missing, stopping service before startForeground.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = createNotification(roomId, roomName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            TravelModeState.updateState(isRunning = true, roomId = roomId, roomName = roomName)
            requestLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during startForeground: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun requestLocationUpdates() {
        if (isLocationRequesting) return
        if (!LocationPermissionHelper.hasLocationPermission(this)) return

        try {
            val locationRequest = LocationRequest.Builder(LOCATION_PRIORITY, LOCATION_INTERVAL_MS)
                .setMinUpdateDistanceMeters(LOCATION_MIN_DISPLACEMENT_M)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isLocationRequesting = true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on requestLocationUpdates: ${e.message}", e)
        }
    }

    private fun removeLocationUpdates() {
        if (isLocationRequesting) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location updates: ${e.message}", e)
            }
            isLocationRequesting = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeLocationUpdates()
        TravelModeState.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_travel_mode)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(roomId: String, roomName: String): Notification {
        val contentIntent = RoomMapActivity.newIntent(this, roomId).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = stopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = getString(R.string.notification_travel_mode_content_format, roomName)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pintrip_pin)
            .setContentTitle(getString(R.string.notification_travel_mode_title))
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }
}
