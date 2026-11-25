package com.pintrip.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pintrip.app.MainActivity
import com.pintrip.app.R
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.ui.common.NotificationPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class NotificationParseResult {
    data class Success(
        val eventId: String,
        val recipientUid: String,
        val roomId: String,
        val kakaoPlaceId: String,
        val title: String?,
        val body: String?
    ) : NotificationParseResult()

    object InvalidType : NotificationParseResult()
    object MissingEventId : NotificationParseResult()
    object MissingRecipientUid : NotificationParseResult()
    object RecipientMismatch : NotificationParseResult()
    object MissingRoomOrPlaceId : NotificationParseResult()
}

object ProcessedEventCache {
    private const val PREFS_NAME = "pintrip_processed_events"
    private const val KEY_EVENT_KEYS = "processed_event_keys"
    private const val MAX_CACHE_SIZE = 100

    @get:Synchronized @set:Synchronized
    var preferenceOverride: SharedPreferences? = null

    private fun getPrefs(context: Context?): SharedPreferences? {
        preferenceOverride?.let { return it }
        return context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun isProcessed(context: Context?, uid: String, eventId: String): Boolean {
        if (uid.isBlank() || eventId.isBlank()) return false
        val prefs = getPrefs(context) ?: return false
        val key = "$uid:$eventId"
        return prefs.contains("evt:$key")
    }

    @Synchronized
    fun markProcessed(context: Context?, uid: String, eventId: String) {
        if (uid.isBlank() || eventId.isBlank()) return
        val prefs = getPrefs(context) ?: return
        val key = "$uid:$eventId"

        val keysString = prefs.getString(KEY_EVENT_KEYS, "") ?: ""
        val keysList = if (keysString.isBlank()) mutableListOf() else keysString.split(",").toMutableList()

        if (!keysList.contains(key)) {
            keysList.add(key)
        }

        while (keysList.size > MAX_CACHE_SIZE) {
            val oldestKey = keysList.removeAt(0)
            prefs.edit().remove("evt:$oldestKey").apply()
        }

        val editor = prefs.edit()
        editor.putBoolean("evt:$key", true)
        editor.putString(KEY_EVENT_KEYS, keysList.joinToString(","))
        editor.apply()
    }

    @Synchronized
    fun clear(context: Context?) {
        val prefs = getPrefs(context) ?: return
        prefs.edit().clear().apply()
    }
}

object NotificationMessageParser {
    fun parse(payload: Map<String, String>, currentUserUid: String?): NotificationParseResult {
        val type = payload["type"]
        if (type != FriendNotificationService.TYPE_PLACE_ADDED) {
            return NotificationParseResult.InvalidType
        }

        val eventId = payload["eventId"]
        if (eventId.isNullOrBlank()) {
            return NotificationParseResult.MissingEventId
        }

        val recipientUid = payload["recipientUid"]
        if (recipientUid.isNullOrBlank()) {
            return NotificationParseResult.MissingRecipientUid
        }

        if (currentUserUid.isNullOrBlank() || currentUserUid != recipientUid) {
            return NotificationParseResult.RecipientMismatch
        }

        val roomId = payload["roomId"]
        val kakaoPlaceId = payload["kakaoPlaceId"]
        if (roomId.isNullOrBlank() || kakaoPlaceId.isNullOrBlank()) {
            return NotificationParseResult.MissingRoomOrPlaceId
        }

        return NotificationParseResult.Success(
            eventId = eventId,
            recipientUid = recipientUid,
            roomId = roomId,
            kakaoPlaceId = kakaoPlaceId,
            title = payload["title"],
            body = payload["body"]
        )
    }
}

class FriendNotificationService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FriendNotificationSvc"

        const val CHANNEL_ID = "friend_place_added_channel"
        const val ACTION_FRIEND_PLACE_ADDED = "com.pintrip.app.ACTION_FRIEND_PLACE_ADDED"

        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_KAKAO_PLACE_ID = "extra_kakao_place_id"
        const val EXTRA_RECIPIENT_UID = "extra_recipient_uid"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_TYPE = "extra_type"

        const val TYPE_PLACE_ADDED = "PLACE_ADDED"

        @get:Synchronized @set:Synchronized
        var notificationDisplayerOverride: ((id: Int, notification: Notification) -> Boolean)? = null

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = context.getString(R.string.channel_friend_place_added_name)
                val descriptionText = context.getString(R.string.channel_friend_place_added_description)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun cancelFriendNotifications(context: Context) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNotifs = notificationManager.activeNotifications
                    for (sbn in activeNotifs) {
                        if (sbn.notification.channelId == CHANNEL_ID) {
                            notificationManager.cancel(sbn.id)
                        }
                    }
                }
                ProcessedEventCache.clear(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel friend notifications: ${e.message}")
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token received.")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DeviceTokenRepository.getInstance(applicationContext).syncToken(token)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync refreshed FCM token onNewToken: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid
        when (val result = NotificationMessageParser.parse(remoteMessage.data, currentUserUid)) {
            is NotificationParseResult.Success -> {
                if (ProcessedEventCache.isProcessed(applicationContext, result.recipientUid, result.eventId)) {
                    Log.d(TAG, "Ignoring duplicate eventId: ${result.eventId} for uid: ${result.recipientUid}")
                    return
                }

                val title = result.title ?: getString(R.string.notification_friend_place_added_title)
                val body = result.body ?: getString(R.string.notification_friend_place_added_body)
                showNotification(
                    eventId = result.eventId,
                    recipientUid = result.recipientUid,
                    roomId = result.roomId,
                    kakaoPlaceId = result.kakaoPlaceId,
                    title = title,
                    body = body
                )
            }
            is NotificationParseResult.InvalidType -> {
                Log.d(TAG, "Ignoring FCM message: invalid type")
            }
            is NotificationParseResult.MissingEventId -> {
                Log.w(TAG, "Ignoring FCM message: missing eventId")
            }
            is NotificationParseResult.MissingRecipientUid -> {
                Log.w(TAG, "Ignoring FCM message: missing recipientUid")
            }
            is NotificationParseResult.RecipientMismatch -> {
                Log.d(TAG, "Ignoring FCM message: recipientUid mismatch")
            }
            is NotificationParseResult.MissingRoomOrPlaceId -> {
                Log.w(TAG, "Ignoring FCM message: missing roomId or kakaoPlaceId")
            }
        }
    }

    private fun showNotification(
        eventId: String,
        recipientUid: String,
        roomId: String,
        kakaoPlaceId: String,
        title: String,
        body: String
    ) {
        val deviceTokenRepo = DeviceTokenRepository.getInstance(applicationContext)
        if (deviceTokenRepo.isLoggingOut) {
            Log.w(TAG, "Cannot show notification: app is currently logging out.")
            return
        }

        val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUserUid == null || currentUserUid != recipientUid) {
            Log.w(TAG, "Cannot show notification: user mismatch right before display.")
            return
        }

        if (!NotificationPermissionHelper.isNotificationPermissionGranted(this)) {
            Log.w(TAG, "Cannot show notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val notificationManagerCompat = NotificationManagerCompat.from(this)
        if (!notificationManagerCompat.areNotificationsEnabled()) {
            Log.w(TAG, "Cannot show notification: App notifications disabled.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "Cannot show notification: Notification channel disabled.")
                return
            }
        }

        createNotificationChannel(applicationContext)

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_FRIEND_PLACE_ADDED
            data = Uri.parse("pintrip://notification/$roomId/$kakaoPlaceId/$eventId")
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_KAKAO_PLACE_ID, kakaoPlaceId)
            putExtra(EXTRA_RECIPIENT_UID, recipientUid)
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_TYPE, TYPE_PLACE_ADDED)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val requestCode = (roomId + "_" + kakaoPlaceId + "_" + eventId).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val notificationId = eventId.hashCode()
        val builtNotification = notificationBuilder.build()

        val override = notificationDisplayerOverride
        if (override != null) {
            val success = override.invoke(notificationId, builtNotification)
            if (success) {
                ProcessedEventCache.markProcessed(applicationContext, recipientUid, eventId)
            }
            return
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(notificationId, builtNotification)
            ProcessedEventCache.markProcessed(applicationContext, recipientUid, eventId)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException when notifying: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}", e)
        }
    }
}
