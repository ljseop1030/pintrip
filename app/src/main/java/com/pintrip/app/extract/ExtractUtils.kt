package com.pintrip.app.extract

import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace

/**
 * 장소명의 공백을 제거하고 소문자로 변환하여 정규화된 비교용 문자열을 반환합니다.
 */
fun normalizePlaceName(name: String): String {
    return name.replace("\\s+".toRegex(), "").lowercase()
}

/**
 * 여러 스크린샷/추출 결과([responses])의 장소 리스트를 이름(공백, 대소문자 무시) 기준으로 중복 제거한
 * 통합 [GeminiExtractionResponse]를 생성합니다. (docs/data-contract.md §8 규격 준수)
 *
 * - 중복 제거 기준: [normalizePlaceName] 기반 (최초 인스턴스 유지)
 */
fun mergeGeminiExtractionResponses(responses: List<GeminiExtractionResponse>): GeminiExtractionResponse {
    val seenNames = mutableSetOf<String>()
    val mergedPlaces = mutableListOf<GeminiPlace>()

    for (response in responses) {
        for (place in response.places) {
            val key = normalizePlaceName(place.name)
            if (key.isNotBlank() && seenNames.add(key)) {
                mergedPlaces.add(place)
            }
        }
    }

    val firstRegionHint = responses.firstNotNullOfOrNull { it.regionHint?.ifBlank { null } }

    return GeminiExtractionResponse(
        places = mergedPlaces,
        regionHint = firstRegionHint,
    )
}

/**
 * 선택된 카카오 후보의 주소(또는 Gemini extracted.outsideRoomRegion)가 방 지역([roomRegion]) 밖인지 판정합니다. (docs/data-contract.md §9 준수)
 *
 * Region mismatch = true 판정 조건:
 * 1. Gemini 추출 시 outsideRoomRegion == true
 * 2. 검색 후 선택된 후보의 주소([candidateAddress])가 방 지역([roomRegion]) 밖인 경우 (메모리 내 비교)
 *
 * @param candidateAddress 선택된 카카오 후보의 도로명/지번 주소 (예: "서울 노원구 공릉로 232")
 * @param roomRegion 방의 대표 지역명 (예: "부산")
 * @param geminiOutsideRoomRegion Gemini가 사전에 판정한 outsideRoomRegion 값
 * @return 방 지역 밖인 경우 true, 방 지역 내이거나 판정 불가 시 false
 */
fun isCandidateAddressOutsideRoomRegion(
    candidateAddress: String?,
    roomRegion: String?,
    geminiOutsideRoomRegion: Boolean = false,
): Boolean {
    if (geminiOutsideRoomRegion) return true

    val trimmedRoomRegion = roomRegion?.trim()?.ifBlank { null } ?: return false
    val trimmedAddress = candidateAddress?.trim()?.ifBlank { null } ?: return false

    // 주소에 방 지역명이 포함되어 있지 않으면 방 지역 밖으로 판정
    return !trimmedAddress.contains(trimmedRoomRegion)
}
