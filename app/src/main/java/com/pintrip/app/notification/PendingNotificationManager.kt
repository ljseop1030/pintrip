package com.pintrip.app.notification

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

data class PendingNotificationDestination(
    val roomId: String,
    val kakaoPlaceId: String,
    val recipientUid: String,
    val requestId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

object PendingNotificationManager {
    private const val PREFS_NAME = "pintrip_pending_notif_prefs"
    private const val KEY_ROOM_ID = "pending_room_id"
    private const val KEY_KAKAO_PLACE_ID = "pending_kakao_place_id"
    private const val KEY_RECIPIENT_UID = "pending_recipient_uid"
    private const val KEY_REQUEST_ID = "pending_request_id"
    private const val KEY_TIMESTAMP = "pending_timestamp"

    @get:Synchronized @set:Synchronized
    var preferenceOverride: SharedPreferences? = null

    @get:Synchronized @set:Synchronized
    private var activeProcessingRequestId: String? = null

    @get:Synchronized @set:Synchronized
    var navigatorOverride: ((context: Context, roomId: String, kakaoPlaceId: String) -> Boolean)? = null

    private fun getPrefs(context: Context?): SharedPreferences? {
        preferenceOverride?.let { return it }
        return context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun savePendingDestination(context: Context?, destination: PendingNotificationDestination) {
        if (destination.roomId.isBlank() || destination.recipientUid.isBlank()) return
        val prefs = getPrefs(context) ?: return
        prefs.edit()
            .putString(KEY_ROOM_ID, destination.roomId)
            .putString(KEY_KAKAO_PLACE_ID, destination.kakaoPlaceId)
            .putString(KEY_RECIPIENT_UID, destination.recipientUid)
            .putString(KEY_REQUEST_ID, destination.requestId)
            .putLong(KEY_TIMESTAMP, destination.timestamp)
            .apply()
        activeProcessingRequestId = null
    }

    @Synchronized
    fun getPendingDestination(context: Context?): PendingNotificationDestination? {
        val prefs = getPrefs(context) ?: return null
        val roomId = prefs.getString(KEY_ROOM_ID, null)
        val kakaoPlaceId = prefs.getString(KEY_KAKAO_PLACE_ID, "") ?: ""
        val recipientUid = prefs.getString(KEY_RECIPIENT_UID, null)
        val requestId = prefs.getString(KEY_REQUEST_ID, null) ?: UUID.randomUUID().toString()
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)

        if (roomId.isNullOrBlank() || recipientUid.isNullOrBlank()) {
            return null
        }

        return PendingNotificationDestination(
            roomId = roomId,
            kakaoPlaceId = kakaoPlaceId,
            recipientUid = recipientUid,
            requestId = requestId,
            timestamp = timestamp
        )
    }

    /**
     * 현재 저장된 알림 목적지를 단일 실행(Single-Flight)으로 획득합니다.
     * 이미 동기 처리 중인 requestId인 경우 null을 반환하여 중복 처리를 차단합니다.
     */
    @Synchronized
    fun acquireProcessingDestination(context: Context?): PendingNotificationDestination? {
        val destination = getPendingDestination(context) ?: return null
        if (activeProcessingRequestId == destination.requestId) {
            return null
        }
        activeProcessingRequestId = destination.requestId
        return destination
    }

    @Synchronized
    fun releaseProcessing(requestId: String) {
        if (activeProcessingRequestId == requestId) {
            activeProcessingRequestId = null
        }
    }

    @Synchronized
    fun consumeDestination(context: Context?, requestId: String): Boolean {
        val current = getPendingDestination(context)
        if (current != null && current.requestId == requestId) {
            clearPendingDestination(context)
            return true
        }
        releaseProcessing(requestId)
        return false
    }

    @Synchronized
    fun clearPendingDestination(context: Context?) {
        activeProcessingRequestId = null
        val prefs = getPrefs(context) ?: return
        prefs.edit().clear().apply()
    }

    @Synchronized
    fun clearIfRequestIdMatches(context: Context?, requestId: String) {
        consumeDestination(context, requestId)
    }
}
