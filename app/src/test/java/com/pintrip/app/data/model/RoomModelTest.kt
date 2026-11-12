package com.pintrip.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomModelTest {

    @Test
    fun testRoomDefaultValues() {
        val room = Room()
        assertEquals("", room.id)
        assertEquals("", room.name)
        assertEquals("", room.region)
        assertNull(room.startDate)
        assertNull(room.endDate)
        assertEquals("sea", room.icon)
        assertEquals("", room.inviteCode)
        assertEquals("", room.ownerId)
        assertTrue(room.memberIds.isEmpty())
        assertTrue(room.members.isEmpty())
        assertEquals(0, room.placeCount)
        assertNull(room.createdAt)
        assertNull(room.updatedAt)
    }

    @Test
    fun testMemberDefaultValues() {
        val member = Member(nickname = "민지", avatarColor = "#FF5722")
        assertEquals("민지", member.nickname)
        assertEquals("#FF5722", member.avatarColor)
        assertNull(member.lastSeenAt)
    }

    @Test
    fun testInviteCodeDefaultValues() {
        val inviteCode = InviteCode(roomId = "room_123")
        assertEquals("room_123", inviteCode.roomId)
        assertNull(inviteCode.createdAt)
    }
}
