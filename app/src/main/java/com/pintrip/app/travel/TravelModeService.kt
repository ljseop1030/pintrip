package com.pintrip.app.travel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.map.RoomMapActivity
import com.pintrip.app.place.PlaceDetailActivity
import com.pintrip.app.ui.common.KoreanJosa
import com.pintrip.app.ui.common.LocationPermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TravelModeService : Service() {

    companion object {
        private const val TAG = "TravelModeService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "travel_mode_channel"
        private const val NEARBY_CHANNEL_ID = "nearby_place_channel"

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fetchJob: Job? = null

    private val roomRepository = RoomRepository()
    private val placeDetailRepository = PlaceDetailRepository()

    private var currentRoomId: String? = null
    private var currentRoomName: String? = null
    private var nearbyCandidates: List<NearbyCandidate> = emptyList()
    private val alreadyNotifiedIds = mutableSetOf<String>()
    private var lastLocation: Location? = null
    private var isFetchCompleted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            Log.d(TAG, "${location.latitude}, ${location.longitude}")
            lastLocation = location

            if (isFetchCompleted) {
                checkNearbyPlaces(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
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

        val isRoomChanged = (currentRoomId != roomId)
        currentRoomId = roomId
        currentRoomName = roomName

        if (isRoomChanged) {
            fetchJob?.cancel()
            nearbyCandidates = emptyList()
            alreadyNotifiedIds.clear()
            isFetchCompleted = false
        }

        val initialStatusText = if (isFetchCompleted) {
            getString(R.string.notification_travel_mode_loaded, roomName, nearbyCandidates.size)
        } else {
            getString(R.string.notification_travel_mode_loading, roomName)
        }

        try {
            val notification = createNotification(roomId, initialStatusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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

        if (isRoomChanged || (!isFetchCompleted && fetchJob?.isActive != true)) {
            loadPlacesForRoom(roomId, roomName)
        }

        return START_NOT_STICKY
    }

    private fun loadPlacesForRoom(roomId: String, roomName: String) {
        fetchJob?.cancel()
        fetchJob = serviceScope.launch {
            updateForegroundNotification(
                createNotification(roomId, getString(R.string.notification_travel_mode_loading, roomName))
            )
            try {
                val room = roomRepository.getRoom(roomId)
                if (room == null || room.region.isBlank()) {
                    Log.w(TAG, "Room or region is empty for roomId: $roomId")
                    onFetchFailed(roomId, roomName)
                    return@launch
                }

                val details = placeDetailRepository.getPlaceDetails(roomId, room.region)
                val candidates = details
                    .filter { it.status == PlaceDetail.STATUS_FOUND && it.lat != null && it.lng != null && it.place.kakaoPlaceId.isNotBlank() }
                    .map { NearbyCandidate(it.place.kakaoPlaceId, it.name.orEmpty(), it.lat!!, it.lng!!) }

                nearbyCandidates = candidates
                isFetchCompleted = true

                updateForegroundNotification(
                    createNotification(roomId, getString(R.string.notification_travel_mode_loaded, roomName, candidates.size))
                )

                lastLocation?.let { location ->
                    checkNearbyPlaces(location)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to load place details for roomId: $roomId", e)
                onFetchFailed(roomId, roomName)
            }
        }
    }

    private fun onFetchFailed(roomId: String, roomName: String) {
        isFetchCompleted = false
        updateForegroundNotification(
            createNotification(roomId, getString(R.string.notification_travel_mode_load_failed, roomName))
        )
    }

    private fun checkNearbyPlaces(location: Location) {
        val roomId = currentRoomId ?: return
        val newNearby = NearbyPlaceFinder.findNewNearby(
            lat = location.latitude,
            lng = location.longitude,
            candidates = nearbyCandidates,
            alreadyNotifiedIds = alreadyNotifiedIds,
            radiusMeters = NEARBY_RADIUS_M.toDouble()
        )
        Log.d(TAG, "근처 장소 ${newNearby.size}곳")

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()

        for ((candidate, distance) in newNearby) {
            alreadyNotifiedIds.add(candidate.kakaoPlaceId)
            if (!notificationsEnabled) {
                Log.i(TAG, "Notification permission is disabled. Skipping nearby notification for ${candidate.name}")
                continue
            }
            showNearbyNotification(roomId, candidate, distance)
        }
    }

    private fun showNearbyNotification(roomId: String, candidate: NearbyCandidate, distance: Int) {
        val roomMapIntent = RoomMapActivity.newIntent(this, roomId)
        val placeDetailIntent = PlaceDetailActivity.newIntent(this, roomId, candidate.kakaoPlaceId)

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntent(roomMapIntent)
            addNextIntent(placeDetailIntent)
            getPendingIntent(
                candidate.kakaoPlaceId.hashCode(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val particle = KoreanJosa.subjectParticle(candidate.name)
        val notification = NotificationCompat.Builder(this, NEARBY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pintrip_pin)
            .setContentTitle(getString(R.string.notification_nearby_place_title, candidate.name, particle))
            .setContentText(getString(R.string.notification_nearby_place_content, distance))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(candidate.kakaoPlaceId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException showing nearby notification for ${candidate.name}", e)
        }
    }

    private fun updateForegroundNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
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
        fetchJob?.cancel()
        serviceScope.cancel()
        removeLocationUpdates()
        TravelModeState.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val travelModeChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_travel_mode),
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(travelModeChannel)

            val nearbyChannel = NotificationChannel(
                NEARBY_CHANNEL_ID,
                getString(R.string.notification_channel_nearby_place),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager?.createNotificationChannel(nearbyChannel)
        }
    }

    private fun createNotification(roomId: String, statusText: String): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pintrip_pin)
            .setContentTitle(getString(R.string.notification_travel_mode_title))
            .setContentText(statusText)
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
