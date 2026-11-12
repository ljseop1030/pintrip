package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore `rooms/{roomId}` 내 `members` Map 필드 저장용 데이터 모델.
 *
 * docs/data-contract.md 규격을 준수합니다:
 * - nickname: 회원 닉네임
 * - avatarColor: 아바타 색상 (#RRGGBB)
 * - lastSeenAt: 방 마지막 방문 시각 (생성 시 null)
 */
@Keep
data class Member(
    val nickname: String = "",
    val avatarColor: String = "#3A4A90",
    @get:ServerTimestamp
    val lastSeenAt: Timestamp? = null,
)
