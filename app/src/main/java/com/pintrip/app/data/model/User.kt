package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore `users/{uid}` 컬렉션 저장용 User 데이터 모델.
 *
 * docs/data-contract.md 규격을 준수합니다:
 * - nickname: 닉네임
 * - email: 이메일
 * - avatarColor: 아바타 프로필 색상 (#RRGGBB)
 * - createdAt: 생성 시각
 * - 비밀번호는 Firestore에 저장하지 않습니다.
 */
@Keep
data class User(
    val nickname: String = "",
    val email: String = "",
    val avatarColor: String = "#3A4A90",
    @get:ServerTimestamp
    val createdAt: Timestamp? = null,
)
