package com.pintrip.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRepositoryTest {

    @Test
    fun testGenerateInviteCodeFormatAndCharset() {
        // 100회 무작위 생성을 통해 길이 및 디폴트 문자셋(0, O, 1, I 제외) 검증
        val excludedChars = setOf('0', 'O', '1', 'I')
        repeat(100) {
            val code = RoomRepository.generateInviteCode()
            assertEquals(6, code.length)
            code.forEach { char ->
                assertTrue("허용된 문자셋에 포함되어야 함: $char", RoomRepository.INVITE_CODE_CHARSET.contains(char))
                assertFalse("0, O, 1, I는 제외되어야 함: $char", excludedChars.contains(char))
            }
        }
    }

    @Test
    fun testValidIconsSet() {
        val expectedIcons = setOf("sea", "food", "nature", "city", "healing")
        assertEquals(expectedIcons, RoomRepository.VALID_ICONS)
    }

    @Test
    fun testJoinRoomResultSealedClassExposesRoom() {
        val sampleRoom = com.pintrip.app.data.model.Room(id = "r1", name = "제주 여행", inviteCode = "K7P2QX")

        val creatorResult: JoinRoomResult = JoinRoomResult.Creator(sampleRoom)
        val existingResult: JoinRoomResult = JoinRoomResult.ExistingMember(sampleRoom)
        val newJoinResult: JoinRoomResult = JoinRoomResult.NewJoin(sampleRoom)
        val recheckedResult: JoinRoomResult = JoinRoomResult.Rechecked(sampleRoom)

        assertEquals(sampleRoom, creatorResult.room)
        assertEquals(sampleRoom, existingResult.room)
        assertEquals(sampleRoom, newJoinResult.room)
        assertEquals(sampleRoom, recheckedResult.room)

        assertTrue(creatorResult is JoinRoomResult.Creator)
        assertTrue(existingResult is JoinRoomResult.ExistingMember)
        assertTrue(newJoinResult is JoinRoomResult.NewJoin)
        assertTrue(recheckedResult is JoinRoomResult.Rechecked)
    }
}
