package com.pintrip.app.extract

import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.remote.GeminiExtractor

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

/**
 * 공유 텍스트([shareText])와 페이지 본문([pageText])을 결합한 텍스트를 생성합니다.
 *
 * - 공유 텍스트와 본문이 모두 존재하는 경우 두 텍스트를 결합합니다.
 * - [maxLength]를 초과하여 자를 때는 공유 텍스트([shareText])가 잘리지 않도록
 *   공유 텍스트를 우선 배치하고, 남은 길이만큼 페이지 본문을 자릅니다.
 * - [shareText]와 [pageText]가 동일하거나 한쪽이 비어있는 경우 중복을 방지합니다.
 *
 * @param shareText 공유 입력에서 온 텍스트 (예: 네이버지도/카카오맵 공유 텍스트)
 * @param pageText 웹페이지에서 크롤링한 본문 텍스트 (또는 fallback 텍스트)
 * @param maxLength Gemini 전달용 최대 텍스트 길이 제한 (기본값: [GeminiExtractor.MAX_TEXT_LENGTH])
 * @return 결합 및 길이 제한이 적용된 텍스트
 */
fun combineShareAndPageText(
    shareText: String,
    pageText: String,
    maxLength: Int = GeminiExtractor.MAX_TEXT_LENGTH,
): String {
    val trimmedShare = shareText.trim()
    val trimmedPage = pageText.trim()

    if (trimmedShare.isBlank()) {
        return trimmedPage.take(maxLength)
    }
    if (trimmedPage.isBlank() || trimmedShare == trimmedPage) {
        return trimmedShare.take(maxLength)
    }

    val separator = "\n\n"
    if (trimmedShare.length >= maxLength) {
        return trimmedShare.take(maxLength)
    }

    val availablePageLength = maxLength - trimmedShare.length - separator.length
    if (availablePageLength <= 0) {
        return trimmedShare
    }

    val truncatedPage = trimmedPage.take(availablePageLength)
    return "$trimmedShare$separator$truncatedPage"
}

