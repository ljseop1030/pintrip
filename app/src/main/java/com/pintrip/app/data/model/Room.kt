package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp

/**
 * Firestore `rooms/{roomId}` 컬렉션 저장용 Room 데이터 모델.
 *
 * docs/data-contract.md 규격을 준수합니다:
 * - id: Firestore 문서 ID (@get:Exclude로 저장 필드와 구분)
 * - name: 방 이름
 * - region: 한글 지역명 ("서울", "부산", "제주", "강릉", "경주", "여수" 등)
 * - startDate: 여행 시작일 (null 가능)
 * - endDate: 여행 종료일 (null 가능)
 * - icon: 메인 아이콘 ("sea", "food", "nature", "city", "healing")
 * - inviteCode: 6자리 초대 코드
 * - ownerId: 방 생성자 Auth UID
 * - memberIds: 방 참여자 UID 목록
 * - members: 방 참여자 상세 Map (UID -> Member)
 * - placeCount: 등록된 장소 개수 (기초값 0)
 * - createdAt: 서버 생성 시각
 * - updatedAt: 방 목록 정렬용 서버 시각
 */
@Keep
data class Room(
    @get:Exclude
    var id: String = "",
    val name: String = "",
    val region: String = "",
    val startDate: Timestamp? = null,
    val endDate: Timestamp? = null,
    val icon: String = "sea",
    val inviteCode: String = "",
    val ownerId: String = "",
    val memberIds: List<String> = emptyList(),
    val members: Map<String, Member> = emptyMap(),
    val placeCount: Int = 0,
    @get:ServerTimestamp
    val createdAt: Timestamp? = null,
    @get:ServerTimestamp
    val updatedAt: Timestamp? = null,
)
