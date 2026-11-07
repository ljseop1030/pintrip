package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore rooms/{roomId}/places/{kakaoPlaceId} 서브컬렉션 저장용 Place 데이터 모델.
 *
 * 카카오 Local API의 검색 결과 데이터(장소명, 주소, 좌표, 카테고리 등)는 저장하지 않고,
 * kakaoPlaceId와 kakaoPlaceUrl만 저장합니다. (docs/data-contract.md 준수)
 */
@Keep
data class Place(
    val kakaoPlaceId: String = "",
    val kakaoPlaceUrl: String = "",
    val searchKeyword: String = "",
    val searchRegion: String? = null,
    val matchType: String = "",
    val sourceType: String = "",
    val sourceUrl: String? = null,
    val addedBy: String = "",
    val addedByNickname: String = "",
    val likedBy: List<String> = emptyList(),
    val likeCount: Int = 0,
    @get:ServerTimestamp
    val createdAt: Timestamp? = null,
)
