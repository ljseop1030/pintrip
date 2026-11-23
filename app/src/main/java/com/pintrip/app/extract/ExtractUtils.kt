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

private val LEVEL_1_REGIONS = setOf(
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
)

private val PROVINCE_MAP = mapOf(
    "서울특별시" to "서울",
    "서울시" to "서울",
    "부산광역시" to "부산",
    "부산시" to "부산",
    "대구광역시" to "대구",
    "대구시" to "대구",
    "인천광역시" to "인천",
    "인천시" to "인천",
    "광주광역시" to "광주",
    "대전광역시" to "대전",
    "대전시" to "대전",
    "울산광역시" to "울산",
    "울산시" to "울산",
    "세종특별자치시" to "세종",
    "세종시" to "세종",
    "경기도" to "경기",
    "강원특별자치도" to "강원",
    "강원도" to "강원",
    "충청북도" to "충북",
    "충북" to "충북",
    "충청남도" to "충남",
    "충남" to "충남",
    "전라북도" to "전북",
    "전북특별자치도" to "전북",
    "전북" to "전북",
    "전라남도" to "전남",
    "전남" to "전남",
    "경상북도" to "경북",
    "경북" to "경북",
    "경상남도" to "경남",
    "경남" to "경남",
    "제주특별자치도" to "제주",
    "제주도" to "제주",
)

/**
 * 방 지역명 또는 카카오 주소의 시/도/광역시/특별자치도/특별시 등의 명칭을 정규화합니다.
 *
 * - "영도", "진도", "완도" 등 2자 짧은 지명의 "도"가 잘리는 현상을 방지합니다.
 * - "특별자치도/특별자치시/광역시/특별시" 접미사를 우선 제거하고, "구/군/시"는 3자 이상인 경우에만 제거합니다.
 * - 도(道) 단위의 길고 정식인 명칭은 카카오 Local API 표기 방식(약칭)으로 변환합니다.
 * - 이 정규화 값은 메모리 상의 [isCandidateAddressOutsideRoomRegion] 지역 판정에만 사용하며,
 *   Firestore나 모델 객체에 저장되는 데이터는 절대 변경하지 않습니다 (docs/data-contract.md §1 규칙 5 준수).
 */
fun normalizeRegionName(region: String?): String {
    val trimmed = region?.trim().orEmpty()
    if (trimmed.isBlank()) return ""

    PROVINCE_MAP[trimmed]?.let { return it }

    for ((full, short) in PROVINCE_MAP) {
        if (trimmed.startsWith(full)) return short
    }

    var result = trimmed
    val longSuffixes = listOf("특별자치도", "특별자치시", "광역시", "특별시")
    for (suffix in longSuffixes) {
        if (result.endsWith(suffix) && result.length > suffix.length) {
            result = result.removeSuffix(suffix)
            break
        }
    }

    val shortSuffixes = listOf("구", "군", "시")
    for (suffix in shortSuffixes) {
        if (result.endsWith(suffix) && result.length > 2) {
            result = result.removeSuffix(suffix)
            break
        }
    }

    return result.trim()
}

data class RegionParts(
    val level1: String? = null,
    val level2: String? = null,
)

/**
 * 주소 또는 지역 문자열을 광역 단위(level1) 및 기초 단위(level2)로 파싱합니다.
 */
fun parseRegionParts(input: String?): RegionParts {
    val trimmed = input?.trim().orEmpty()
    if (trimmed.isBlank()) return RegionParts()

    val words = trimmed.split("\\s+".toRegex())
    if (words.isEmpty()) return RegionParts()

    val firstNormalized = normalizeRegionName(words[0])

    if (words.size == 1) {
        return if (firstNormalized in LEVEL_1_REGIONS) {
            RegionParts(level1 = firstNormalized, level2 = null)
        } else {
            RegionParts(level1 = null, level2 = firstNormalized)
        }
    }

    val secondNormalized = normalizeRegionName(words[1])

    return if (firstNormalized in LEVEL_1_REGIONS) {
        RegionParts(level1 = firstNormalized, level2 = secondNormalized.ifBlank { null })
    } else {
        RegionParts(level1 = null, level2 = firstNormalized)
    }
}

/**
 * 선택된 카카오 후보의 주소(또는 Gemini extracted.outsideRoomRegion)가 방 지역([roomRegion]) 밖인지 판정합니다. (docs/data-contract.md §9 준수)
 *
 * Region mismatch = true 판정 조건:
 * 1. Gemini 추출 시 outsideRoomRegion == true
 * 2. 검색 후 선택된 후보의 주소([candidateAddress])가 방 지역([roomRegion]) 밖인 경우 (행정구역 계층 단위 메모리 내 비교)
 *
 * @param candidateAddress 선택된 카카오 후보의 도로명/지번 주소 (예: "서울 노원구 공릉로 232")
 * @param roomRegion 방의 대표 지역명 (예: "부산", "부산광역시", "전라남도", "영도", "광주광역시")
 * @param geminiOutsideRoomRegion Gemini가 사전에 판정한 outsideRoomRegion 값
 * @return 방 지역 밖인 경우 true, 방 지역 내이거나 판정 불가 시 false
 */
fun isCandidateAddressOutsideRoomRegion(
    candidateAddress: String?,
    roomRegion: String?,
    geminiOutsideRoomRegion: Boolean = false,
): Boolean {
    if (geminiOutsideRoomRegion) return true

    val roomParts = parseRegionParts(roomRegion)
    if (roomParts.level1 == null && roomParts.level2 == null) return false

    val candidateParts = parseRegionParts(candidateAddress)
    if (candidateParts.level1 == null && candidateParts.level2 == null) return false

    // 1. 방 지역에 광역 단위(level1, 예: "부산", "서울", "광주", "전남")가 명시된 경우
    if (roomParts.level1 != null) {
        if (candidateParts.level1 != roomParts.level1) {
            return true
        }
    }

    // 2. 방 지역에 기초 단위(level2, 예: "영도", "해운대", "광주" in 경기 광주시)가 명시된 경우
    if (roomParts.level2 != null) {
        if (candidateParts.level2 != roomParts.level2) {
            return true
        }
    }

    return false
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

