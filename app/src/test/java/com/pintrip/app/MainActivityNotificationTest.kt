package com.pintrip.app

import com.pintrip.app.notification.FriendNotificationService
import com.pintrip.app.notification.PendingNotificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MainActivityNotificationTest {

    @Test
    fun pendingNotificationDestination_creation() {
        val data = PendingNotificationDestination(
            roomId = "room_123",
            kakaoPlaceId = "place_456",
            recipientUid = "user_789",
            requestId = "req_001"
        )

        assertEquals("room_123", data.roomId)
        assertEquals("place_456", data.kakaoPlaceId)
        assertEquals("user_789", data.recipientUid)
        assertEquals("req_001", data.requestId)
    }

    @Test
    fun pendingNotificationDestination_uniqueRequestIds() {
        val destA = PendingNotificationDestination(
            roomId = "room_123",
            kakaoPlaceId = "place_456",
            recipientUid = "user_789"
        )

        val destB = PendingNotificationDestination(
            roomId = "room_123",
            kakaoPlaceId = "place_999",
            recipientUid = "user_789"
        )

        assertNotEquals(destA.requestId, destB.requestId)
    }

    @Test
    fun notificationConstants_verifyKeys() {
        assertEquals("friend_place_added_channel", FriendNotificationService.CHANNEL_ID)
        assertEquals("com.pintrip.app.ACTION_FRIEND_PLACE_ADDED", FriendNotificationService.ACTION_FRIEND_PLACE_ADDED)
        assertEquals("extra_room_id", FriendNotificationService.EXTRA_ROOM_ID)
        assertEquals("extra_kakao_place_id", FriendNotificationService.EXTRA_KAKAO_PLACE_ID)
        assertEquals("extra_recipient_uid", FriendNotificationService.EXTRA_RECIPIENT_UID)
        assertEquals("extra_event_id", FriendNotificationService.EXTRA_EVENT_ID)
        assertEquals("extra_type", FriendNotificationService.EXTRA_TYPE)
        assertEquals("PLACE_ADDED", FriendNotificationService.TYPE_PLACE_ADDED)
    }
}
