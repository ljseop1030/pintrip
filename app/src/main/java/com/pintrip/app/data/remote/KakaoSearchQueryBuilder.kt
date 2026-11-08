package com.pintrip.app.data.remote

import androidx.annotation.Keep

/**
 * 카카오 키워드 검색 시 사용할 파라미터 조합 (검색어 문자열 및 카테고리 그룹 코드).
 *
 * @property query 카카오 키워드 검색어 (예: "부산 해운대 모모스커피")
 * @property categoryGroupCode 카카오 Local API 카테고리 그룹 코드 (FD6, CE7, AT4, AD5 또는 null)
 */
@Keep
data class KakaoSearchQuery(
    val query: String,
    val categoryGroupCode: String? = null,
)

/**
 * docs/data-contract.md §8 (Search strategy) 규격에 따라
 * 카카오 검색어(query)와 카테고리 그룹 코드(categoryGroupCode)를 생성합니다.
 *
 * @param name 장소명 (Gemini 추출 장소명 또는 사용자 입력 키워드)
 * @param roomRegion 방 문서의 region 필드 값 (변환 없이 그대로 전달)
 * @param region Gemini 추출 장소별 region 값 (places[].region)
 * @param categoryHint Gemini 추출 장소별 categoryHint (dining, cafe, sights, stay 등)
 * @param outsideRoomRegion 방 지역 외 장소 여부 (places[].outsideRoomRegion)
 * @param regionHint 이미지 전체 지역 힌트 (regionHint)
 * @return [KakaoSearchQuery] 생성된 검색어 및 카테고리 그룹 코드
 */
fun buildKakaoSearchQuery(
    name: String,
    roomRegion: String,
    region: String? = null,
    categoryHint: String? = null,
    outsideRoomRegion: Boolean = false,
    regionHint: String? = null,
): KakaoSearchQuery {
    val cleanName = name.trim()

    val prefix = if (outsideRoomRegion) {
        // outsideRoomRegion == true: "{region or regionHint}"
        val targetRegion = region?.trim()?.ifBlank { null } ?: regionHint?.trim()?.ifBlank { null }
        targetRegion.orEmpty()
    } else {
        // outsideRoomRegion == false: "{room region} {region}"
        val roomRegionTrimmed = roomRegion.trim()
        val placeRegion = region?.trim().orEmpty()

        val prefixParts = mutableListOf<String>()
        if (roomRegionTrimmed.isNotBlank() && !placeRegion.startsWith(roomRegionTrimmed)) {
            prefixParts.add(roomRegionTrimmed)
        }
        if (placeRegion.isNotBlank()) prefixParts.add(placeRegion)
        prefixParts.joinToString(" ")
    }

    val fullQuery = if (prefix.isNotBlank()) {
        "$prefix $cleanName"
    } else {
        cleanName
    }

    val categoryGroupCode = mapCategoryHintToGroupCode(categoryHint)

    return KakaoSearchQuery(
        query = fullQuery,
        categoryGroupCode = categoryGroupCode,
    )
}

/**
 * Gemini categoryHint를 카카오 Local API category_group_code로 매핑합니다.
 * - dining -> FD6 (음식점)
 * - cafe -> CE7 (카페)
 * - sights -> AT4 (관광명소)
 * - stay -> AD5 (숙박)
 * - 기타 -> null
 */
fun mapCategoryHintToGroupCode(categoryHint: String?): String? {
    return when (categoryHint?.trim()?.lowercase()) {
        "dining" -> "FD6"
        "cafe" -> "CE7"
        "sights" -> "AT4"
        "stay" -> "AD5"
        else -> null
    }
}
