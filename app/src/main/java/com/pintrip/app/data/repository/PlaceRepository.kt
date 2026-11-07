package com.pintrip.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.pintrip.app.data.model.Place
import kotlinx.coroutines.tasks.await

/**
 * 장소 추가 시 추가 성공한 개수와 중복으로 건너뛴 개수를 전달하는 결과 모델.
 *
 * @property addedCount 실제 Firestore에 새롭게 추가된 장소 개수
 * @property skippedCount 중복(요청 목록 내 중복 또는 Firestore에 이미 존재하는 장소)으로 건너뛴 개수
 */
data class AddPlacesResult(
    val addedCount: Int,
    val skippedCount: Int,
)

/**
 * Firestore `rooms/{roomId}/places` 서브컬렉션을 관리하는 Repository.
 *
 * docs/data-contract.md 및 AGENTS.md 규격을 준수합니다:
 * 1. Transaction을 사용하여 저장 전 모든 기존 장소를 조회(Read)한 뒤, 존재하지 않는 장소만 추가(Set).
 * 2. 기존 장소가 존재하는 경우 덮어쓰지 않고 스킵하여 작성자 및 좋아요 정보 유지.
 * 3. 신규 추가되는 장소는 likedBy=[], likeCount=0, createdAt=null(@get:ServerTimestamp)로 깨끗하게 초기화.
 * 4. 신규 추가된 개수만큼 부모 문서 `rooms/{roomId}`의 `placeCount`를 `FieldValue.increment`로 업데이트.
 * 5. 방 문서가 존재하지 않을 경우 `IllegalArgumentException` 발생.
 * 6. 생성 시각(`createdAt`) 내림차순(최신순)으로 목록 1회 조회 (`getPlaces`).
 */
class PlaceRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    companion object {
        private val VALID_MATCH_TYPES = setOf("auto", "confirmed", "manual")
        private val VALID_SOURCE_TYPES = setOf("screenshot", "link")
    }

    /**
     * 지정한 방에 장소 목록을 추가합니다.
     *
     * - 요청 목록 내 동일 `kakaoPlaceId` 중복을 1차 제거합니다.
     * - 로그인한 사용자의 `uid`와 `place.addedBy` 일치 여부 및 필드 값 유효성(matchType, sourceType 등)을 검증합니다.
     * - 신규 장소는 likedBy=[], likeCount=0, createdAt=null로 초기화하여 Firestore 서버 시각이 들어가도록 합니다.
     * - Firestore Transaction을 사용해 기존 장소는 건너뛰고 새 장소만 set하며, `placeCount`를 증가시킵니다.
     *
     * @param roomId 대상 방 ID
     * @param places 추가할 장소 목록
     * @return [AddPlacesResult] 추가된 개수와 중복 스킵된 개수
     * @throws IllegalArgumentException 방 ID가 비어있거나, 검증 실패, 또는 방 문서가 존재하지 않는 경우
     * @throws IllegalStateException 사용자가 로그인되어 있지 않은 경우
     */
    suspend fun addPlaces(roomId: String, places: List<Place>): AddPlacesResult {
        require(roomId.isNotBlank()) { "roomId는 비어 있을 수 없습니다." }
        if (places.isEmpty()) {
            return AddPlacesResult(addedCount = 0, skippedCount = 0)
        }

        // 1. 요청 목록 자체 내 중복 ID 제거 (첫 번째 장소 유지)
        val distinctPlaces = places.distinctBy { it.kakaoPlaceId }
        val requestDuplicatesCount = places.size - distinctPlaces.size

        // 2. 로그인 사용자 검증
        val currentUser = auth.currentUser
        checkNotNull(currentUser) { "로그인된 사용자 정보가 없습니다. (Auth currentUser is null)" }
        val currentUid = currentUser.uid

        // 3. 장소 입력 데이터 유효성 검증
        distinctPlaces.forEach { place ->
            require(place.kakaoPlaceId.isNotBlank()) { "kakaoPlaceId는 필수입니다." }
            require(place.kakaoPlaceUrl.isNotBlank()) { "kakaoPlaceUrl은 필수입니다." }
            require(place.searchKeyword.isNotBlank()) { "searchKeyword는 필수입니다." }
            require(place.addedBy.isNotBlank()) { "addedBy는 필수입니다." }
            require(place.addedBy == currentUid) {
                "addedBy($place.addedBy)가 현재 로그인된 사용자의 uid($currentUid)와 일치하지 않습니다."
            }
            require(place.addedByNickname.isNotBlank()) { "addedByNickname은 필수입니다." }
            require(place.matchType in VALID_MATCH_TYPES) {
                "matchType은 'auto', 'confirmed', 'manual' 중 하나여야 합니다. (입력값: ${place.matchType})"
            }
            require(place.sourceType in VALID_SOURCE_TYPES) {
                "sourceType은 'screenshot', 'link' 중 하나여야 합니다. (입력값: ${place.sourceType})"
            }
        }

        val roomRef = db.collection("rooms").document(roomId)

        // 4. Firestore Transaction (모든 Read 실행 후 Write 진행)
        return db.runTransaction { transaction ->
            // (a) Read: 방 문서 존재 여부 확인
            val roomSnapshot = transaction.get(roomRef)
            if (!roomSnapshot.exists()) {
                throw IllegalArgumentException("존재하지 않는 방입니다: $roomId")
            }

            // (b) Read: 저장하려는 모든 장소 문서 미리 읽기
            val placesCollection = roomRef.collection("places")
            val existingSnapshots = distinctPlaces.associate { place ->
                val placeRef = placesCollection.document(place.kakaoPlaceId)
                place.kakaoPlaceId to transaction.get(placeRef)
            }

            // (c) Write 계산: 신규 장소와 기존 장소 분리 및 초기값 재설정
            val newPlaces = mutableListOf<Place>()
            var dbSkippedCount = 0

            for (place in distinctPlaces) {
                val snapshot = existingSnapshots[place.kakaoPlaceId]
                if ((snapshot != null) && snapshot.exists()) {
                    dbSkippedCount++
                } else {
                    // 신규 장소 초기화: likedBy 비우기, likeCount 0, createdAt null(서버 타임스탬프 자동 부여)
                    val placeToSave = place.copy(
                        likedBy = emptyList(),
                        likeCount = 0,
                        createdAt = null,
                    )
                    newPlaces.add(placeToSave)
                }
            }

            // (d) Write: 신규 장소 추가 및 placeCount 업데이트
            for (newPlace in newPlaces) {
                val placeRef = placesCollection.document(newPlace.kakaoPlaceId)
                transaction.set(placeRef, newPlace)
            }

            if (newPlaces.isNotEmpty()) {
                transaction.update(roomRef, "placeCount", FieldValue.increment(newPlaces.size.toLong()))
            }

            AddPlacesResult(
                addedCount = newPlaces.size,
                skippedCount = dbSkippedCount + requestDuplicatesCount,
            )
        }.await()
    }

    /**
     * 지정한 방의 장소 목록을 생성 시각(createdAt) 내림차순(최신순)으로 1회 조회합니다.
     *
     * @param roomId 대상 방 ID
     * @param source 데이터 소스 ([Source.DEFAULT], [Source.SERVER], [Source.CACHE]). 실제 서버 조회를 강제하려면 [Source.SERVER] 지정
     * @return 생성 시각 최신순으로 정렬된 [Place] 목록
     * @throws IllegalArgumentException 방 문서가 존재하지 않는 경우
     * @throws com.google.firebase.firestore.FirebaseFirestoreException [Source.SERVER] 사용 시 오프라인/네트워크/권한 실패 시 예외 발생
     */
    suspend fun getPlaces(roomId: String, source: Source = Source.DEFAULT): List<Place> {
        require(roomId.isNotBlank()) { "roomId는 비어 있을 수 없습니다." }

        val roomRef = db.collection("rooms").document(roomId)

        // 방 존재 여부 확인 (지정한 Source 사용)
        val roomSnapshot = roomRef.get(source).await()
        if (!roomSnapshot.exists()) {
            throw IllegalArgumentException("존재하지 않는 방입니다: $roomId")
        }

        // 최신순 장소 목록 조회
        val snapshot = roomRef.collection("places")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get(source)
            .await()

        return snapshot.toObjects(Place::class.java)
    }
}
