package com.pintrip.app.notification

import com.pintrip.app.room.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FriendNotificationServiceTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        ProcessedEventCache.preferenceOverride = fakePrefs
        ProcessedEventCache.clear(null)
    }

    @After
    fun tearDown() {
        ProcessedEventCache.preferenceOverride = null
    }

    @Test
    fun parse_validPayload_returnsSuccess() {
        val payload = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "user_123",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789",
            "title" to "새 장소 추가",
            "body" to "민지님이 장소를 추가했습니다."
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = "user_123")

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals("evt_100", success.eventId)
        assertEquals("user_123", success.recipientUid)
        assertEquals("room_abc", success.roomId)
        assertEquals("place_789", success.kakaoPlaceId)
        assertEquals("새 장소 추가", success.title)
        assertEquals("민지님이 장소를 추가했습니다.", success.body)
    }

    @Test
    fun parse_invalidType_returnsInvalidType() {
        val payload = mapOf(
            "type" to "OTHER_EVENT",
            "eventId" to "evt_100",
            "recipientUid" to "user_123",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789"
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.InvalidType, result)
    }

    @Test
    fun parse_missingEventId_returnsMissingEventId() {
        val payload = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "",
            "recipientUid" to "user_123",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789"
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.MissingEventId, result)
    }

    @Test
    fun parse_missingRecipientUid_returnsMissingRecipientUid() {
        val payload = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789"
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.MissingRecipientUid, result)
    }

    @Test
    fun parse_recipientMismatch_returnsRecipientMismatch() {
        val payload = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "user_999",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789"
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.RecipientMismatch, result)
    }

    @Test
    fun parse_unauthenticated_returnsRecipientMismatch() {
        val payload = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "user_123",
            "roomId" to "room_abc",
            "kakaoPlaceId" to "place_789"
        )

        val result = NotificationMessageParser.parse(payload, currentUserUid = null)
        assertEquals(NotificationParseResult.RecipientMismatch, result)
    }

    @Test
    fun parse_missingRoomOrPlaceId_returnsMissingRoomOrPlaceId() {
        val payload1 = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "user_123",
            "roomId" to "",
            "kakaoPlaceId" to "place_789"
        )
        val result1 = NotificationMessageParser.parse(payload1, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.MissingRoomOrPlaceId, result1)

        val payload2 = mapOf(
            "type" to "PLACE_ADDED",
            "eventId" to "evt_100",
            "recipientUid" to "user_123",
            "roomId" to "room_abc",
            "kakaoPlaceId" to ""
        )
        val result2 = NotificationMessageParser.parse(payload2, currentUserUid = "user_123")
        assertEquals(NotificationParseResult.MissingRoomOrPlaceId, result2)
    }

    @Test
    fun processedEventCache_persistsAndDistinguishesUsers() {
        val uid1 = "user_1"
        val uid2 = "user_2"
        val eventId = "evt_duplicate_check"

        assertFalse(ProcessedEventCache.isProcessed(null, uid1, eventId))

        ProcessedEventCache.markProcessed(null, uid1, eventId)
        assertTrue(ProcessedEventCache.isProcessed(null, uid1, eventId))

        // Different user receives same event ID -> not marked as processed for user 2
        assertFalse(ProcessedEventCache.isProcessed(null, uid2, eventId))

        // Clear cache for current user
        ProcessedEventCache.clear(null)
        assertFalse(ProcessedEventCache.isProcessed(null, uid1, eventId))
    }
}
