package com.pintrip.app.data.model

import androidx.annotation.Keep
import java.io.Serializable

/**
 * Firestore [Place] 정보와 카카오 Local API 키워드 검색(재조회) 결과를 결합한 메모리 전용 데이터 모델.
 *
 * docs/data-contract.md §4 규격을 준수합니다:
 * - place: Firestore에 저장된 장소 객체
 * - status: 재조회 상태 ("found" / "notFound")
 * - name: 카카오 장소명 (place_name)
 * - address: 도로명 주소 (없으면 지번 주소)
 * - lat: 위도 (Double)
 * - lng: 경도 (Double)
 * - category: 매핑된 카테고리 ("dining", "cafe", "sights", "stay", "etc")
 * - phone: 전화번호
 */
@Keep
data class PlaceDetail(
    val place: Place = Place(),
    val status: String = STATUS_NOT_FOUND,
    val name: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val category: String? = null,
    val phone: String? = null,
) : Serializable {

    companion object {
        const val STATUS_FOUND = "found"
        const val STATUS_NOT_FOUND = "notFound"

        /**
         * 카카오 키워드 검색 결과([doc])로부터 status가 "found"인 [PlaceDetail]을 생성합니다.
         */
        fun fromKakaoDocument(place: Place, doc: KakaoPlaceDocument): PlaceDetail {
            return PlaceDetail(
                place = place,
                status = STATUS_FOUND,
                name = doc.placeName,
                address = doc.displayAddress,
                lat = doc.lat,
                lng = doc.lng,
                category = doc.mappedCategory,
                phone = doc.phone.ifBlank { null },
            )
        }

        /**
         * 카카오 키워드 검색에서 장소를 찾지 못했거나 실패했을 때 status가 "notFound"인 [PlaceDetail]을 생성합니다.
         */
        fun createNotFound(place: Place): PlaceDetail {
            return PlaceDetail(
                place = place,
                status = STATUS_NOT_FOUND,
                name = null,
                address = null,
                lat = null,
                lng = null,
                category = null,
                phone = null,
            )
        }
    }
}
