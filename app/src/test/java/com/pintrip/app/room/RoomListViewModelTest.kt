package com.pintrip.app.room

import com.pintrip.app.R
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.JoinRoomResult
import com.pintrip.app.data.repository.RoomJoinException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomListViewModelTest {

    @Test
    fun testJoinRoomExceptionToResIdMapping() {
        val notLoggedIn: Exception = RoomJoinException.NotLoggedIn()
        val profileReq: Exception = RoomJoinException.ProfileRequired("프로필 없음")
        val invalidCode: Exception = RoomJoinException.InvalidCodeFormat()
        val codeNotFound: Exception = RoomJoinException.CodeNotFound()
        val networkErr: Exception = RoomJoinException.NetworkUnavailable(RuntimeException("network"))
        val permDenied: Exception = RoomJoinException.PermissionDenied()
        val unknownErr: Exception = RoomJoinException.Unknown("unknown")

        fun mapException(e: Exception): Int {
            return when (e) {
                is RoomJoinException.NotLoggedIn -> R.string.error_not_logged_in
                is RoomJoinException.ProfileRequired -> R.string.error_profile_check_required
                is RoomJoinException.InvalidCodeFormat -> R.string.error_invalid_invite_code_format
                is RoomJoinException.CodeNotFound -> R.string.error_invite_code_not_found
                is RoomJoinException.NetworkUnavailable -> R.string.error_network_unavailable
                is RoomJoinException.PermissionDenied -> R.string.error_room_join_permission_denied
                is RoomJoinException.Unknown -> R.string.error_room_join_default
                else -> R.string.error_room_join_default
            }
        }

        assertEquals(R.string.error_not_logged_in, mapException(notLoggedIn))
        assertEquals(R.string.error_profile_check_required, mapException(profileReq))
        assertEquals(R.string.error_invalid_invite_code_format, mapException(invalidCode))
        assertEquals(R.string.error_invite_code_not_found, mapException(codeNotFound))
        assertEquals(R.string.error_network_unavailable, mapException(networkErr))
        assertEquals(R.string.error_room_join_permission_denied, mapException(permDenied))
        assertEquals(R.string.error_room_join_default, mapException(unknownErr))
    }

    @Test
    fun testJoinRoomResultDataHandling() {
        val sampleRoom = Room(id = "room_123", name = "부산 여행", inviteCode = "K7P2QX")
        val newJoin = JoinRoomResult.NewJoin(sampleRoom)
        val rechecked = JoinRoomResult.Rechecked(sampleRoom)

        assertEquals("room_123", newJoin.room.id)
        assertEquals("부산 여행", newJoin.room.name)
        assertEquals("room_123", rechecked.room.id)
    }
}
