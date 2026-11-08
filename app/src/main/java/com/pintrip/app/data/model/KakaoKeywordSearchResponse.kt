package com.pintrip.app.data.model

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

/**
 * 카카오 Local API 키워드 검색 응답의 장소(document) 정보 데이터 모델.
 *
 * docs/data-contract.md 규격에 맞춰 필드를 구성하였습니다:
 * - id: Kakao 장소 ID (kakaoPlaceId)
 * - placeName: 장소명
 * - categoryName: 전체 카테고리 텍스트 (예: "음식점 > 카페 > 커피전문점")
 * - categoryGroupCode: 카테고리 그룹 코드 (FD6, CE7, AT4, CT1, AD5 등)
 * - categoryGroupName: 카테고리 그룹명
 * - phone: 전화번호
 * - addressName: 지번 주소
 * - roadAddressName: 도로명 주소 (fallback으로 addressName 사용)
 * - x: 경도 (Longitude, String -> Double 변환용)
 * - y: 위도 (Latitude, String -> Double 변환용)
 * - placeUrl: 카카오 장소 상세 URL (kakaoPlaceUrl)
 * - distance: 중심좌표까지의 거리 (미터)
 */
@Keep
data class KakaoPlaceDocument(
    val id: String = "",
    val placeName: String = "",
    val categoryName: String = "",
    val categoryGroupCode: String = "",
    val categoryGroupName: String = "",
    val phone: String = "",
    val addressName: String = "",
    val roadAddressName: String = "",
    val x: String = "",
    val y: String = "",
    val placeUrl: String = "",
    val distance: String = "",
) {
    /**
     * 도로명 주소가 존재하면 도로명 주소를, 없으면 지번 주소를 반환합니다. (docs/data-contract.md §4 준수)
     */
    val displayAddress: String
        get() = roadAddressName.ifBlank { addressName }

    /**
     * 위도(y) String을 Double로 변환합니다. (변환 실패 시 null)
     */
    val lat: Double?
        get() = y.toDoubleOrNull()

    /**
     * 경도(x) String을 Double로 변환합니다. (변환 실패 시 null)
     */
    val lng: Double?
        get() = x.toDoubleOrNull()

    /**
     * categoryGroupCode를 App 내부 카테고리로 매핑합니다. (docs/data-contract.md §4 준수)
     * - FD6 -> dining
     * - CE7 -> cafe
     * - AT4, CT1 -> sights
     * - AD5 -> stay
     * - 기타 -> etc
     */
    val mappedCategory: String
        get() = when (categoryGroupCode) {
            "FD6" -> "dining"
            "CE7" -> "cafe"
            "AT4", "CT1" -> "sights"
            "AD5" -> "stay"
            else -> "etc"
        }
}

/**
 * 카카오 Local API 검색 결과의 동일 이름 검색 정보.
 */
@Keep
data class KakaoSameName(
    val region: List<String> = emptyList(),
    val keyword: String = "",
    val selectedRegion: String = "",
)

/**
 * 카카오 Local API 검색 결과 메타 데이터.
 */
@Keep
data class KakaoSearchMeta(
    val totalCount: Int = 0,
    val pageableCount: Int = 0,
    val isEnd: Boolean = true,
    val sameName: KakaoSameName? = null,
)

/**
 * 카카오 Local API 키워드 검색 응답 루트 모델.
 */
@Keep
data class KakaoKeywordSearchResponse(
    val meta: KakaoSearchMeta = KakaoSearchMeta(),
    val documents: List<KakaoPlaceDocument> = emptyList(),
)

/**
 * 카카오 Local API 키워드 검색 결과 JSON 문자열을 [KakaoKeywordSearchResponse] 객체로 파싱합니다.
 *
 * 외부 라이브러리(Gson, Jackson, kotlinx.serialization 등) 없이 안드로이드 프레임워크 표준 [JSONObject]를 활용합니다.
 *
 * @param jsonString 카카오 API 응답 JSON 문자열
 * @return 파싱된 [KakaoKeywordSearchResponse]
 * @throws org.json.JSONException JSON 구조가 올바르지 않은 경우
 */
fun parseKakaoKeywordSearchResponse(jsonString: String): KakaoKeywordSearchResponse {
    val jsonObject = JSONObject(jsonString)

    val metaObject = jsonObject.optJSONObject("meta")
    val meta = if (metaObject != null) {
        val sameNameObject = metaObject.optJSONObject("same_name")
        val sameName = if (sameNameObject != null) {
            val regionArray = sameNameObject.optJSONArray("region")
            val regionList = mutableListOf<String>()
            if (regionArray != null) {
                for (i in 0 until regionArray.length()) {
                    regionList.add(regionArray.optStringNonNull(i))
                }
            }
            KakaoSameName(
                region = regionList,
                keyword = sameNameObject.optStringNonNull("keyword"),
                selectedRegion = sameNameObject.optStringNonNull("selected_region"),
            )
        } else {
            null
        }

        KakaoSearchMeta(
            totalCount = metaObject.optInt("total_count", 0),
            pageableCount = metaObject.optInt("pageable_count", 0),
            isEnd = metaObject.optBoolean("is_end", true),
            sameName = sameName,
        )
    } else {
        KakaoSearchMeta()
    }

    val documentsArray = jsonObject.optJSONArray("documents")
    val documents = mutableListOf<KakaoPlaceDocument>()
    if (documentsArray != null) {
        for (i in 0 until documentsArray.length()) {
            val docObj = documentsArray.optJSONObject(i) ?: continue
            documents.add(
                KakaoPlaceDocument(
                    id = docObj.optStringNonNull("id"),
                    placeName = docObj.optStringNonNull("place_name"),
                    categoryName = docObj.optStringNonNull("category_name"),
                    categoryGroupCode = docObj.optStringNonNull("category_group_code"),
                    categoryGroupName = docObj.optStringNonNull("category_group_name"),
                    phone = docObj.optStringNonNull("phone"),
                    addressName = docObj.optStringNonNull("address_name"),
                    roadAddressName = docObj.optStringNonNull("road_address_name"),
                    x = docObj.optStringNonNull("x"),
                    y = docObj.optStringNonNull("y"),
                    placeUrl = docObj.optStringNonNull("place_url"),
                    distance = docObj.optStringNonNull("distance"),
                ),
            )
        }
    }

    return KakaoKeywordSearchResponse(
        meta = meta,
        documents = documents,
    )
}

private fun JSONObject.optStringNonNull(key: String, defaultValue: String = ""): String {
    if (isNull(key)) return defaultValue
    return optString(key, defaultValue)
}

private fun JSONArray.optStringNonNull(index: Int, defaultValue: String = ""): String {
    if (isNull(index)) return defaultValue
    return optString(index, defaultValue)
}
