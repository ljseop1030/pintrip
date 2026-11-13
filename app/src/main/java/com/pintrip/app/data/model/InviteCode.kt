package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore `inviteCodes/{code}` 컬렉션 저장용 InviteCode 데이터 모델.
 *
 * docs/data-contract.md 규격을 준수합니다:
 * - roomId: 초대 코드가 가리키는 방 ID (문서 ID = inviteCode)
 * - createdAt: 생성 시각
 */
@Keep
data class InviteCode(
    val roomId: String = "",
    @get:ServerTimestamp
    val createdAt: Timestamp? = null,
)
