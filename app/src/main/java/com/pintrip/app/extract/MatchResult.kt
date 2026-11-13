package com.pintrip.app.extract

import androidx.annotation.Keep
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.model.KakaoPlaceDocument
import java.io.Serializable

/**
 * 장소 매칭 결과 후보 정보 데이터 모델 (메모리 전용, Firestore 저장 안 함).
 *
 * docs/data-contract.md §9 규격을 준수합니다:
 * - kakaoPlaceId: 카카오 장소 ID
 * - kakaoPlaceUrl: 카카오 장소 상세 URL
 * - name: 카카오 장소명 (place_name)
 * - address: 도로명 주소 (없으면 지번 주소)
 * - lat: 위도 (Double)
 * - lng: 경도 (Double)
 * - category: 매핑된 앱 카테고리 ("dining", "cafe", "sights", "stay", "etc")
 * - phone: 전화번호
 */
@Keep
data class MatchCandidate(
    val kakaoPlaceId: String = "",
    val kakaoPlaceUrl: String = "",
    val name: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val category: String? = null,
    val phone: String? = null,
) : Serializable {
    companion object {
        /**
         * [KakaoPlaceDocument] 응답 객체에서 [MatchCandidate] 객체를 생성합니다.
         */
        fun fromKakaoDocument(doc: KakaoPlaceDocument): MatchCandidate {
            return MatchCandidate(
                kakaoPlaceId = doc.id,
                kakaoPlaceUrl = doc.placeUrl,
                name = doc.placeName,
                address = doc.displayAddress,
                lat = doc.lat,
                lng = doc.lng,
                category = doc.mappedCategory,
                phone = doc.phone.ifBlank { null },
            )
        }
    }
}

/**
 * Gemini 추출 장소별 매칭 결과 모델 (메모리 전용, Firestore 저장 안 함).
 *
 * docs/data-contract.md §9 규격을 준수합니다:
 * - localId: 화면 식별용 ID (`img{screenshotIndex}_p{order}`)
 * - extracted: Gemini 추출 결과 장소 객체 ([GeminiPlace])
 * - status: 매칭 상태 ("MATCHED" / "CHECK" / "NOT_FOUND")
 * - candidates: 카카오 검색 후보 리스트 (최대 5개)
 * - selectedPlaceId: 선택된 카카오 장소 ID (MATCHED 시 자동 설정, CHECK 시 사용자 선택)
 * - regionMismatch: 방 지역 외 장소 여부
 * - checked: 장소 선택 체크박스 여부 (NOT_FOUND 시 false, regionMismatch 시 기본 false)
 */
@Keep
data class MatchResult(
    val localId: String = "",
    val extracted: GeminiPlace = GeminiPlace(),
    val status: String = STATUS_CHECK,
    val candidates: List<MatchCandidate> = emptyList(),
    val selectedPlaceId: String? = null,
    val regionMismatch: Boolean = false,
    val checked: Boolean = true,
    val sourceType: String = "screenshot",
    val sourceUrl: String? = null,
) : Serializable {
    /**
     * 현재 [selectedPlaceId]에 해당하는 [MatchCandidate] 객체를 반환합니다.
     */
    val selectedCandidate: MatchCandidate?
        get() = candidates.firstOrNull { it.kakaoPlaceId == selectedPlaceId }

    /**
     * Firestore 저장 가능 상태인지 여부를 반환합니다 (checked가 true이고 선택된 candidate가 있는 경우).
     */
    val isConfirmable: Boolean
        get() = checked && selectedCandidate != null

    companion object {
        const val STATUS_MATCHED = "MATCHED"
        const val STATUS_CHECK = "CHECK"
        const val STATUS_NOT_FOUND = "NOT_FOUND"

        /**
         * 카카오 검색 결과 후보 목록([candidates])과 Gemini 추출 장소([extracted]) 정보를 바탕으로
         * status, selectedPlaceId, checked 기본값을 판단하여 [MatchResult]를 생성합니다. (docs/data-contract.md §9 규격 준수)
         *
         * Status 규칙:
         * 1. 후보 0개: NOT_FOUND
         * 2. 후보 1개: MATCHED (자동 선택)
         * 3. 후보 2개 이상 & 공백/대소문자 무시 장소명이 정확히 1개 일치: MATCHED (자동 선택)
         * 4. 후보 2개 이상 기타: CHECK (상위 5개 후보)
         *
         * Checked 규칙:
         * - NOT_FOUND: false (선택 비활성화)
         * - regionMismatch 또는 outsideRoomRegion: false (사용자 확인 필요)
         * - 그 외: true
         */
        fun create(
            localId: String,
            extracted: GeminiPlace,
            candidates: List<MatchCandidate>,
            regionMismatch: Boolean = false,
            sourceType: String = "screenshot",
            sourceUrl: String? = null,
        ): MatchResult {
            val cleanExtractedName = normalizePlaceName(extracted.name)

            val exactMatches = candidates.filter { candidate ->
                normalizePlaceName(candidate.name) == cleanExtractedName
            }

            val (status, autoSelectedId) = when {
                candidates.isEmpty() -> {
                    STATUS_NOT_FOUND to null
                }

                candidates.size == 1 -> {
                    STATUS_MATCHED to candidates.first().kakaoPlaceId
                }

                exactMatches.size == 1 -> {
                    STATUS_MATCHED to exactMatches.first().kakaoPlaceId
                }

                else -> {
                    STATUS_CHECK to null
                }
            }

            val isRegionMismatchFinal = regionMismatch || extracted.outsideRoomRegion

            val initialChecked = when {
                status == STATUS_NOT_FOUND -> false
                isRegionMismatchFinal -> false
                else -> true
            }

            return MatchResult(
                localId = localId,
                extracted = extracted,
                status = status,
                candidates = candidates.sortedByDescending { it.kakaoPlaceId == autoSelectedId }.take(5),
                selectedPlaceId = autoSelectedId,
                regionMismatch = isRegionMismatchFinal,
                checked = initialChecked,
                sourceType = sourceType,
                sourceUrl = sourceUrl,
            )
        }
    }
}
